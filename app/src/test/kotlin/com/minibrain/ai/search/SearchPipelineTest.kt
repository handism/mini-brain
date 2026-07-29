package com.minibrain.ai.search

import androidx.sqlite.db.SupportSQLiteQuery
import com.minibrain.ai.agent.DateRange
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.ai.rag.SourceType
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class SearchPipelineTest {

    private lateinit var queryExpander: QueryExpander
    private lateinit var llmReranker: LlmReranker
    private lateinit var ragPipeline: RagPipeline
    private lateinit var chunkDao: ChunkDao
    private lateinit var documentDao: DocumentDao
    private lateinit var hyde: HyDE
    private lateinit var searchPipeline: SearchPipeline
    private lateinit var cache: SearchRequestCache

    @Before
    fun setup() {
        // Plant Timber fake
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}
        })

        queryExpander = mockk()
        llmReranker = mockk()
        ragPipeline = mockk()
        chunkDao = mockk()
        documentDao = mockk()
        hyde = mockk()
        cache = mockk()

        searchPipeline = SearchPipeline(
            queryExpander = queryExpander,
            llmReranker = llmReranker,
            ragPipeline = ragPipeline,
            chunkDao = chunkDao,
            documentDao = documentDao,
            hyde = hyde
        )
    }

    private fun citation(docId: Long, heading: String, source: SourceType = SourceType.UNKNOWN, score: Float = 0f, topicMatch: Boolean = false) =
        Citation(headingPath = heading, snippet = "test", docId = docId, source = source, score = score, topicMatch = topicMatch)

    @Test
    fun `search executes successfully and merges candidates from different sources`() = runTest {
        val query = "test query"
        val treeUri = "tree/uri"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns emptyList()
        coEvery { cache.chunkVectors() } returns Pair(emptyList(), emptyArray())
        coEvery { cache.treeUri } returns treeUri

        val bm25Citation = citation(1, "A", SourceType.BM25)
        val vectorCitation = citation(2, "B", SourceType.VECTOR, score = 0.5f)
        val metaCitation = citation(3, "C", SourceType.METADATA)

        // Mock BM25 search
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns listOf(
            ChunkEntity(id = 1, docId = 1, text = "bm25 text", embedding = ByteArray(0), headingPath = "A")
        )

        // Mock Vector search
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns listOf(vectorCitation)

        // Mock Reranker
        coEvery { llmReranker.rerank(query, any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val candidates = it.invocation.args[1] as List<Citation>
            candidates.take(10)
        }

        val result = searchPipeline.search(query, treeUri, cache = cache)

        // The reranker is mocked to just return the candidates it receives.
        // We expect at least the vector and bm25 candidates.
        val docIds = result.citations.map { it.docId }
        assertTrue(docIds.contains(1L))
        assertTrue(docIds.contains(2L))
    }

    @Test
    fun `search uses HyDE generated query if available`() = runTest {
        val query = "test query"
        val treeUri = "tree/uri"
        val hypothetical = "hypothetical answer"

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns hypothetical
        coEvery { cache.documents() } returns emptyList()
        coEvery { cache.chunkVectors() } returns Pair(emptyList(), emptyArray())
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()
        coEvery { llmReranker.rerank(query, any(), any()) } returns emptyList()

        searchPipeline.search(query, treeUri, cache = cache)

        // Verify vector search was called with the hypothetical query
        coVerify { ragPipeline.vectorOnlyTopK(hypothetical, treeUri, any(), cache) }
    }

    @Test
    fun `search pins date range hits to the top`() = runTest {
        val query = "test query"
        val treeUri = "tree/uri"
        val dateRange = DateRange(LocalDate.parse("2023-01-01"), LocalDate.parse("2023-12-31"))

        coEvery { queryExpander.expand(query) } returns listOf(query)
        coEvery { hyde.generateHypothetical(query) } returns null
        coEvery { cache.documents() } returns listOf(
            DocumentEntity(id = 5, treeUri = treeUri, fileUri = "uri", fileName = "test.md", relativePath = "test.md", lastModified = 0L, contentHash = "", firstParagraph = "test", documentDate = "2023-06-01")
        )
        coEvery { cache.chunkVectors() } returns Pair(emptyList(), emptyArray())
        coEvery { chunkDao.bm25SearchByTree(any(), eq(treeUri), any()) } returns emptyList()
        coEvery { ragPipeline.vectorOnlyTopK(any(), treeUri, any(), cache) } returns emptyList()

        // Mock Reranker to put some other citation first, or reverse the list
        val citation5 = citation(5, "test.md", SourceType.METADATA)
        val citation6 = citation(6, "other", SourceType.VECTOR)

        coEvery { llmReranker.rerank(query, any(), any()) } returns listOf(citation6, citation5)

        val result = searchPipeline.search(query, treeUri, dateRange = dateRange, cache = cache)

        // Date range hit should be pinned to the top despite reranker order
        assertEquals(5L, result.citations[0].docId)
        assertEquals(6L, result.citations[1].docId)
    }
}
