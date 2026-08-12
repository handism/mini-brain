package com.minibrain.ai.agent

import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.SourceType
import com.minibrain.ai.search.SearchPipeline
import com.minibrain.ai.search.SearchPipelineResult
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class AgentPipelineTest {

    private val llmService: LlmService = mockk(relaxed = true)
    private val embedderService: EmbedderService = mockk(relaxed = true)
    private val chunkDao: ChunkDao = mockk(relaxed = true)
    private val documentDao: DocumentDao = mockk(relaxed = true)
    private val ragPipeline: RagPipeline = mockk(relaxed = true)
    private val searchPipeline: SearchPipeline = mockk(relaxed = true)
    private val coverageChecker: CoverageChecker = mockk(relaxed = true)

    private lateinit var pipeline: AgentPipeline

    @Before
    fun setUp() {
        // Plant Timber to avoid NPEs when testing
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}
        })

        pipeline = AgentPipeline(
            llmService,
            embedderService,
            chunkDao,
            documentDao,
            ragPipeline,
            searchPipeline,
            coverageChecker
        )

        // Default valid setups
        coEvery { documentDao.getAllByTree(any()) } returns emptyList()
        coEvery { chunkDao.getAllByTree(any()) } returns emptyList()
    }

    @Test
    fun testGeneralKnowledgeQueryBypassesRag() = runTest {
        // "とは" is a general knowledge keyword pattern in QueryClassifier
        val query = "Kotlinとは何ですか？"
        coEvery { llmService.generateStream(any()) } returns flowOf("Kotlin is a programming language.")

        val result = pipeline.run(query, "content://test")

        // RAG bypass should return no citations
        assertTrue(result.citations.isEmpty())

        // SearchPipeline shouldn't be touched
        coVerify(exactly = 0) { searchPipeline.search(any(), any(), any(), any(), any()) }
    }

    @Test
    fun testSearchPipelineSuccess() = runTest {
        val query = "昨日の日記の内容を教えて"
        val mockCitation = Citation("path/note.md", "日記の内容", docId = 1L, source = SourceType.METADATA)

        coEvery {
            searchPipeline.search(any(), any(), any(), any(), any())
        } returns SearchPipelineResult(listOf(mockCitation), emptyList())

        coEvery {
            coverageChecker.check(any(), any())
        } returns CoverageResult(canAnswer = true, missingInformation = emptyList())

        coEvery { llmService.generateStream(any()) } returns flowOf("昨日の日記について...")

        val result = pipeline.run(query, "content://test")

        // Citations should be returned from SearchPipeline
        assertEquals(1, result.citations.size)
        assertEquals(mockCitation, result.citations.first())

        // Ensure Rag fallback wasn't needed
        coVerify(exactly = 0) { ragPipeline.retrieveTopChunks(any(), any(), any(), any()) }
    }

    @Test
    fun testFallbackToReActLoop() = runTest {
        val query = "去年の夏休みの思い出"

        // SearchPipeline returns empty -> triggers ReAct loop
        coEvery {
            searchPipeline.search(any(), any(), any(), any(), any())
        } returns SearchPipelineResult(emptyList(), emptyList())

        every { llmService.isReady() } returns true

        // ReAct loop simulation:
        // 1. Planner Prompt asks for a decision. We simulate it deciding to Finalize directly.
        coEvery { llmService.generateStream(any()) } returns flowOf("ACTION: finalize\nREASON: わかりません")

        val result = pipeline.run(query, "content://test")

        // The pipeline will exit the ReAct loop gracefully on Finalize
        // However, since it finalized without finding citations, it falls into the forced RRF fallback
        // We will assert the forced RRF fallback was called because citations were STILL empty
        coVerify(exactly = 1) { ragPipeline.retrieveTopChunks(any(), any(), any(), any()) }
    }

    @Test
    fun testForcedRrfFallback() = runTest {
        val query = "よくわからないクエリ"
        val fallbackCitation = Citation("path/fallback.md", "フォールバック内容", docId = 2L, source = SourceType.RRF)

        coEvery {
            searchPipeline.search(any(), any(), any(), any(), any())
        } returns SearchPipelineResult(emptyList(), emptyList())

        every { llmService.isReady() } returns true

        // ReAct loop planner outputs gibberish, causing 2 consecutive parse errors
        coEvery { llmService.generateStream(any()) } returns flowOf("何かの文章でフォーマット違反")

        // Mock fallback response
        coEvery {
            ragPipeline.retrieveTopChunks(any(), any(), any(), any())
        } returns listOf(fallbackCitation)

        val result = pipeline.run(query, "content://test")

        // Should return the fallback citation
        assertEquals(1, result.citations.size)
        assertEquals(fallbackCitation, result.citations.first())
        coVerify(exactly = 1) { ragPipeline.retrieveTopChunks(any(), any(), any(), any()) }
    }
}
