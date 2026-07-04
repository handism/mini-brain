package com.minibrain.ai.rag

import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocChunkCount
import com.minibrain.data.db.daos.DocDateRow
import com.minibrain.data.db.daos.DocPathRow
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.sqlite.db.SupportSQLiteQuery

class SearchRequestCacheTest {

    class FakeDocumentDao : DocumentDao {
        var getAllByTreeCount = 0

        override suspend fun getAllByTree(treeUri: String): List<DocumentEntity> {
            getAllByTreeCount++
            kotlinx.coroutines.delay(10)
            return listOf(
                DocumentEntity(
                    id = 1,
                    treeUri = treeUri,
                    fileUri = "fileUri1",
                    fileName = "file1.md",
                    relativePath = "path1",
                    documentDate = null,
                    lastModified = 0L,
                    contentHash = "hash"
                )
            )
        }

        override fun observeAllByTree(treeUri: String): Flow<List<DocumentEntity>> = TODO()
        override fun observeCountByTree(treeUri: String): Flow<Int> = TODO()
        override suspend fun getByFileUri(fileUri: String): DocumentEntity? = TODO()
        override suspend fun insert(doc: DocumentEntity): Long = TODO()
        override suspend fun update(doc: DocumentEntity) = TODO()
        override suspend fun deleteAllByTree(treeUri: String) = TODO()
        override suspend fun deleteByFileUri(fileUri: String) = TODO()
        override suspend fun getById(id: Long): DocumentEntity? = TODO()
        override suspend fun searchByPath(treeUri: String, keyword: String): List<DocumentEntity> = TODO()
        override suspend fun getRecentFiles(treeUri: String, limit: Int): List<DocumentEntity> = TODO()
        override suspend fun getDocDatesByIds(ids: List<Long>): List<DocDateRow> = TODO()
        override suspend fun getDocPathsByIds(ids: List<Long>): List<DocPathRow> = TODO()
        override suspend fun getByDateRange(treeUri: String, start: String, end: String): List<DocumentEntity> = TODO()
    }

    class FakeChunkDao : ChunkDao {
        var getAllByTreeCount = 0
        var dummyChunks = listOf<ChunkEntity>()

        override suspend fun getAllByTree(treeUri: String): List<ChunkEntity> {
            getAllByTreeCount++
            kotlinx.coroutines.delay(10)
            return dummyChunks
        }

        override suspend fun insertAll(chunks: List<ChunkEntity>): List<Long> = TODO()
        override suspend fun getByDoc(docId: Long): List<ChunkEntity> = TODO()
        override suspend fun countByDoc(docId: Long): Int = TODO()
        override suspend fun getChunkCountsGroupedByDoc(): List<DocChunkCount> = TODO()
        override suspend fun getAll(): List<ChunkEntity> = TODO()
        override suspend fun getByScope(treeUri: String, scope: String): List<ChunkEntity> = TODO()
        override fun observeCountByTree(treeUri: String): Flow<Int> = TODO()
        override suspend fun count(): Int = TODO()
        override suspend fun deleteByDoc(docId: Long) = TODO()
        override suspend fun deleteAllByTree(treeUri: String) = TODO()
        override suspend fun bm25SearchRaw(query: SupportSQLiteQuery): List<ChunkEntity> = TODO()
    }

    @Test
    fun `test documents cache miss and hit`() = runBlocking {
        val docDao = FakeDocumentDao()
        val chunkDao = FakeChunkDao()
        val cache = SearchRequestCache("tree", chunkDao, docDao)

        assertEquals(0, docDao.getAllByTreeCount)

        val docs1 = cache.documents()
        assertEquals(1, docDao.getAllByTreeCount)
        assertEquals(1, docs1.size)

        val docs2 = cache.documents()
        assertEquals(1, docDao.getAllByTreeCount)
        assertEquals(1, docs2.size)
    }

    @Test
    fun `test chunkVectors cache miss and hit`() = runBlocking {
        val docDao = FakeDocumentDao()
        val chunkDao = FakeChunkDao()
        chunkDao.dummyChunks = listOf(
            ChunkEntity(
                docId = 1L,
                headingPath = "h1",
                text = "text1",
                embedding = FloatArray(384) { 0f }.let { com.minibrain.ai.embed.EmbedderService.floatArrayToBytes(it) }
            )
        )
        val cache = SearchRequestCache("tree", chunkDao, docDao)

        assertEquals(0, chunkDao.getAllByTreeCount)

        val (chunks1, vectors1) = cache.chunkVectors()
        assertEquals(1, chunkDao.getAllByTreeCount)
        assertEquals(1, chunks1.size)
        assertEquals(1, vectors1.size)

        val (chunks2, vectors2) = cache.chunkVectors()
        assertEquals(1, chunkDao.getAllByTreeCount)
        assertEquals(1, chunks2.size)
        assertEquals(1, vectors2.size)
    }

    @Test
    fun `test cosineTopK returns correctly sorted chunks`() = runBlocking {
        val docDao = FakeDocumentDao()
        val chunkDao = FakeChunkDao()

        val vec1 = FloatArray(384) { 0f }.apply { this[0] = 1f; this[1] = 0f } // Cosine similarity with query: 1.0
        val vec2 = FloatArray(384) { 0f }.apply { this[0] = 0f; this[1] = 1f } // Cosine similarity with query: 0.0

        val chunk1 = ChunkEntity(
            docId = 1L, headingPath = "h1", text = "chunk1",
            embedding = com.minibrain.ai.embed.EmbedderService.floatArrayToBytes(vec1)
        )
        val chunk2 = ChunkEntity(
            docId = 2L, headingPath = "h2", text = "chunk2",
            embedding = com.minibrain.ai.embed.EmbedderService.floatArrayToBytes(vec2)
        )

        chunkDao.dummyChunks = listOf(chunk2, chunk1)
        val cache = SearchRequestCache("tree", chunkDao, docDao)

        val queryVec = FloatArray(384) { 0f }.apply { this[0] = 1f; this[1] = 0f }
        val topK = cache.cosineTopK(queryVec, 2)

        assertEquals(2, topK.size)
        assertEquals(chunk1.text, topK[0].second.text) // chunk1 is more similar
        assertEquals(chunk2.text, topK[1].second.text)
    }

    @Test
    fun `test documents concurrency`() = runBlocking {
        val docDao = FakeDocumentDao()
        val chunkDao = FakeChunkDao()
        val cache = SearchRequestCache("tree", chunkDao, docDao)

        val deferreds = (1..100).map {
            async {
                cache.documents()
            }
        }
        deferreds.awaitAll()

        assertEquals(1, docDao.getAllByTreeCount)
    }
}
