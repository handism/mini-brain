package com.minibrain.ai.agent.tools

import com.minibrain.ai.agent.AgentTool
import com.minibrain.ai.agent.ToolCall
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.RagPipeline
import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import org.json.JSONArray
import org.junit.Test
import timber.log.Timber

class ToolExecutorTest {

    private lateinit var documentDao: DocumentDao
    private lateinit var chunkDao: ChunkDao
    private lateinit var embedderService: EmbedderService
    private lateinit var ragPipeline: RagPipeline
    private lateinit var llmService: LlmService
    private lateinit var cache: SearchRequestCache
    private lateinit var toolExecutor: ToolExecutor

    private val treeUri = "content://dummy/tree"

    private val logs = mutableListOf<String>()

    private val testTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs.add(message)
        }
    }


    @Before
    fun setup() {
        Timber.plant(testTree)
        logs.clear()

        mockkConstructor(JSONArray::class)

        documentDao = mockk()

        chunkDao = mockk()
        embedderService = mockk()
        ragPipeline = mockk()
        llmService = mockk()
        cache = mockk()

        toolExecutor = ToolExecutor(
            documentDao = documentDao,
            chunkDao = chunkDao,
            embedderService = embedderService,
            ragPipeline = ragPipeline,
            treeUri = treeUri,
            llmService = llmService,
            cache = cache,
        )
    }


    @After
    fun teardown() {
        Timber.uproot(testTree)
        unmockkAll()
    }


    @Test
    fun executeReadFile_whenDocumentNotFound_returnsFileNotFound() = runTest {
        coEvery { documentDao.getById(1L) } returns null

        val call = ToolCall(1, AgentTool.ReadFile(docId = 1L, path = null))
        val result = toolExecutor.execute(call)

        assertEquals("FILE NOT FOUND", result.summary)
        assertTrue(result.citations.isEmpty())
    }

    @Test
    fun executeReadFile_withValidJsonHeadingsAndTags_parsesCorrectly() = runTest {
        val docId = 1L
        val doc = DocumentEntity(
            id = docId,
            treeUri = treeUri,
            fileUri = "uri",
            fileName = "test.md",
            relativePath = "test.md",
            lastModified = 0L,
            contentHash = "hash",
            headings = "[\"Header 1\", \"Header 2\"]",
            tags = "[\"tag1\", \"tag2\"]"
        )
        val chunk = ChunkEntity(
            id = 1L,
            docId = docId,
            headingPath = "Header 1",
            text = "Chunk text",
            embedding = ByteArray(0)
        )

        every { anyConstructed<JSONArray>().length() } returnsMany listOf(2, 2)
        every { anyConstructed<JSONArray>().getString(0) } returnsMany listOf("Header 1", "tag1")
        every { anyConstructed<JSONArray>().getString(1) } returnsMany listOf("Header 2", "tag2")

        coEvery { documentDao.getById(docId) } returns doc
        coEvery { chunkDao.getByDoc(docId) } returns listOf(chunk)

        val call = ToolCall(1, AgentTool.ReadFile(docId = docId, path = null))
        val result = toolExecutor.execute(call)

        val expectedSummary = """
            FILE: test.md
            headings: [Header 1, Header 2]
            tags: [tag1, tag2]
            ---
            ## Header 1
            Chunk text
        """.trimIndent()

        assertEquals(expectedSummary, result.summary)
        assertEquals(1, result.citations.size)
        assertEquals("Chunk text", result.citations[0].snippet)
    }

    @Test
    fun executeReadFile_withInvalidJsonHeadingsAndTags_handlesGracefully() = runTest {
        val docId = 1L
        val doc = DocumentEntity(
            id = docId,
            treeUri = treeUri,
            fileUri = "uri",
            fileName = "test.md",
            relativePath = "test.md",
            lastModified = 0L,
            contentHash = "hash",
            headings = "invalid_json_headings",
            tags = "invalid_json_tags"
        )
        val chunk = ChunkEntity(
            id = 1L,
            docId = docId,
            headingPath = "Header 1",
            text = "Chunk text",
            embedding = ByteArray(0)
        )

        every { anyConstructed<JSONArray>().length() } throws org.json.JSONException("invalid json")

        coEvery { documentDao.getById(docId) } returns doc
        coEvery { chunkDao.getByDoc(docId) } returns listOf(chunk)

        val call = ToolCall(1, AgentTool.ReadFile(docId = docId, path = null))
        val result = toolExecutor.execute(call)

        val expectedSummary = """
            FILE: test.md
            ---
            ## Header 1
            Chunk text
        """.trimIndent()

        assertEquals(expectedSummary, result.summary)

        assertTrue(logs.any { it.contains("parseFirstHeadings failed") })
        assertTrue(logs.any { it.contains("parseJsonArray failed") })
    }
}
