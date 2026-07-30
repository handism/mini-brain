package com.minibrain.ai.rag

import androidx.sqlite.db.SupportSQLiteQuery
import com.minibrain.ai.embed.EmbedType
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocDateRow
import com.minibrain.data.db.daos.DocPathRow
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.daos.FolderEmbeddingDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.FolderEmbeddingEntity
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import kotlin.math.exp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class RagPipelineTest {

    private lateinit var embedderService: EmbedderService
    private lateinit var chunkDao: ChunkDao
    private lateinit var documentDao: DocumentDao
    private lateinit var folderEmbeddingDao: FolderEmbeddingDao
    private lateinit var pipeline: RagPipeline

    private val timberTree = object : Timber.Tree() {
        val logs = mutableListOf<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs.add("[$tag] $message")
        }
    }

    @Before
    fun setup() {
        embedderService = mockk()
        chunkDao = mockk()
        documentDao = mockk()
        folderEmbeddingDao = mockk()
        pipeline = RagPipeline(embedderService, chunkDao, documentDao, folderEmbeddingDao)
        Timber.plant(timberTree)
    }

    @After
    fun teardown() {
        Timber.uproot(timberTree)
    }

    @Test
    fun `freshnessBoost returns 0 if date is null`() {
        val boost = RagPipeline.freshnessBoost(null, LocalDate.now())
        assertEquals(0f, boost, 0.0001f)
    }

    @Test
    fun `freshnessBoost returns max boost for today`() {
        val today = LocalDate.now()
        val boost = RagPipeline.freshnessBoost(today, today)
        assertEquals(RagPipeline.FRESHNESS_BOOST_MAX, boost, 0.0001f)
    }

    @Test
    fun `freshnessBoost applies decay for older dates`() {
        val today = LocalDate.now()
        val thirtyDaysAgo = today.minusDays(30)
        val boost30 = RagPipeline.freshnessBoost(thirtyDaysAgo, today)

        assertEquals(RagPipeline.FRESHNESS_BOOST_MAX * exp(-30f / RagPipeline.FRESHNESS_DECAY_DAYS).toFloat(), boost30, 0.0001f)

        val oneYearAgo = today.minusDays(365)
        val boost365 = RagPipeline.freshnessBoost(oneYearAgo, today)
        assertEquals(RagPipeline.FRESHNESS_BOOST_MAX * exp(-365f / RagPipeline.FRESHNESS_DECAY_DAYS).toFloat(), boost365, 0.0001f)

        assertTrue(boost30 > boost365)
    }

    @Test
    fun `freshnessBoost coerces future dates to 0 days difference`() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val boost = RagPipeline.freshnessBoost(tomorrow, today)
        assertEquals(RagPipeline.FRESHNESS_BOOST_MAX, boost, 0.0001f)
    }

    @Test
    fun `vectorOnlyTopK generates correct citations`() = runBlocking {
        val query = "test query"
        val treeUri = "test-tree"
        val mockVec = FloatArray(384) { 0.1f }

        coEvery { embedderService.embed(query, EmbedType.QUERY) } returns mockVec

        val mockChunk = ChunkEntity(
            id = 1L,
            docId = 100L,
            headingPath = "Test Heading",
            text = "Test Text",
            embedding = EmbedderService.floatArrayToBytes(FloatArray(384) { 0.1f })
        )
        coEvery { chunkDao.getAllByTree(treeUri) } returns listOf(mockChunk)

        coEvery { documentDao.getDocPathsByIds(listOf(100L)) } returns listOf(
            DocPathRow(100L, "test/path.md")
        )

        val results = pipeline.vectorOnlyTopK(query, treeUri, k = 10)

        assertEquals(1, results.size)
        val citation = results.first()
        assertEquals("Test Heading", citation.headingPath)
        assertEquals("Test Text", citation.snippet)
        assertEquals(100L, citation.docId)
        assertEquals("test/path.md", citation.relativePath)
        assertEquals(SourceType.VECTOR, citation.source)
        assertTrue(citation.score > 0f)
    }

    @Test
    fun `retrieveTopChunks combines BM25, Vector, and Folder results`() = runBlocking {
        val query = "test"
        val treeUri = "test-tree"
        val mockVec = FloatArray(384) { 0.1f }

        coEvery { embedderService.embed(query, EmbedType.QUERY) } returns mockVec

        val vecChunk = ChunkEntity(
            id = 1L,
            docId = 10L,
            headingPath = "Vec Heading",
            text = "Vec Text",
            embedding = EmbedderService.floatArrayToBytes(FloatArray(384) { 0.1f })
        )
        coEvery { chunkDao.getAllByTree(treeUri) } returns listOf(vecChunk)

        val bm25Chunk = ChunkEntity(
            id = 2L,
            docId = 20L,
            headingPath = "BM25 Heading",
            text = "BM25 Text",
            embedding = ByteArray(0)
        )
        val sqlSlot = slot<SupportSQLiteQuery>()
        coEvery { chunkDao.bm25SearchRaw(capture(sqlSlot)) } returns listOf(bm25Chunk)

        val folderEmbedding = FolderEmbeddingEntity(
            id = 1L,
            path = "folder/path",
            treeUri = treeUri,
            embedding = EmbedderService.floatArrayToBytes(FloatArray(384) { 0.1f })
        )
        coEvery { folderEmbeddingDao.getAllByTree(treeUri) } returns listOf(folderEmbedding)

        val todayStr = LocalDate.now().toString()
        coEvery { documentDao.getDocDatesByIds(any()) } returns listOf(
            DocDateRow(10L, todayStr),
            DocDateRow(20L, todayStr)
        )
        coEvery { documentDao.getDocPathsByIds(any()) } returns listOf(
            DocPathRow(10L, "vec.md"),
            DocPathRow(20L, "bm25.md")
        )

        val results = pipeline.retrieveTopChunks(query, treeUri, topK = 10)

        assertEquals(3, results.size)

        val rrfCitations = results.filter { it.source == SourceType.RRF }
        assertEquals(2, rrfCitations.size)
        assertTrue(rrfCitations.any { it.docId == 10L && it.relativePath == "vec.md" })
        assertTrue(rrfCitations.any { it.docId == 20L && it.relativePath == "bm25.md" })

        val folderCitations = results.filter { it.source == SourceType.FOLDER }
        assertEquals(1, folderCitations.size)
        assertEquals("folder/path", folderCitations.first().relativePath)
        assertEquals("フォルダ: folder/path", folderCitations.first().headingPath)
    }

    @Test
    fun `retrieveTopChunks handles empty results safely`() = runBlocking {
        val query = "test"
        val treeUri = "test-tree"

        coEvery { embedderService.embed(query, EmbedType.QUERY) } returns FloatArray(384) { 0.1f }
        coEvery { chunkDao.getAllByTree(treeUri) } returns emptyList()
        coEvery { chunkDao.bm25SearchRaw(any()) } returns emptyList()
        coEvery { folderEmbeddingDao.getAllByTree(treeUri) } returns emptyList()
        coEvery { documentDao.getDocPathsByIds(emptyList()) } returns emptyList()
        coEvery { documentDao.getDocDatesByIds(emptyList()) } returns emptyList()

        val results = pipeline.retrieveTopChunks(query, treeUri)
        assertTrue(results.isEmpty())
    }
}

