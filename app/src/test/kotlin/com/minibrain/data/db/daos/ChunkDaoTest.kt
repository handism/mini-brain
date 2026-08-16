package com.minibrain.data.db.daos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.entities.ChunkEntity
import com.minibrain.data.db.entities.DocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChunkDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var chunkDao: ChunkDao
    private lateinit var documentDao: DocumentDao

    private var docId1: Long = 0
    private var docId2: Long = 0

    @Before
    fun createDb() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        chunkDao = db.chunkDao()
        documentDao = db.documentDao()

        // Insert dummy documents for foreign key constraints
        val doc1 = DocumentEntity(
            treeUri = "content://tree/1",
            fileUri = "content://file/1",
            fileName = "doc1.md",
            relativePath = "folder1/doc1.md",
            lastModified = 1000L,
            contentHash = "hash1"
        )
        val doc2 = DocumentEntity(
            treeUri = "content://tree/1",
            fileUri = "content://file/2",
            fileName = "doc2.md",
            relativePath = "folder2/doc2.md",
            lastModified = 2000L,
            contentHash = "hash2"
        )

        docId1 = documentDao.insert(doc1)
        docId2 = documentDao.insert(doc2)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAllAndGetByDoc() = runBlocking {
        val chunk1 = ChunkEntity(docId = docId1, headingPath = "# H1", text = "Text 1", embedding = byteArrayOf(1, 2))
        val chunk2 = ChunkEntity(docId = docId1, headingPath = "# H2", text = "Text 2", embedding = byteArrayOf(3, 4))

        chunkDao.insertAll(listOf(chunk1, chunk2))

        val chunks = chunkDao.getByDoc(docId1)
        assertEquals(2, chunks.size)
        assertEquals("Text 1", chunks[0].text)
        assertEquals("Text 2", chunks[1].text)
    }

    @Test
    fun countByDocAndCount() = runBlocking {
        chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "", text = "1", embedding = byteArrayOf()),
            ChunkEntity(docId = docId1, headingPath = "", text = "2", embedding = byteArrayOf()),
            ChunkEntity(docId = docId2, headingPath = "", text = "3", embedding = byteArrayOf())
        ))

        assertEquals(2, chunkDao.countByDoc(docId1))
        assertEquals(1, chunkDao.countByDoc(docId2))
        assertEquals(3, chunkDao.count())
    }

    @Test
    fun getChunkCountsGroupedByDoc() = runBlocking {
        chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "", text = "1", embedding = byteArrayOf()),
            ChunkEntity(docId = docId1, headingPath = "", text = "2", embedding = byteArrayOf()),
            ChunkEntity(docId = docId2, headingPath = "", text = "3", embedding = byteArrayOf())
        ))

        val counts = chunkDao.getChunkCountsGroupedByDoc()
        assertEquals(2, counts.size)

        val countMap = counts.associateBy { it.docId }
        assertEquals(2, countMap[docId1]?.chunkCount)
        assertEquals(1, countMap[docId2]?.chunkCount)
    }

    @Test
    fun getAllAndGetBatchSync() = runBlocking {
        val ids = chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "", text = "1", embedding = byteArrayOf()),
            ChunkEntity(docId = docId1, headingPath = "", text = "2", embedding = byteArrayOf()),
            ChunkEntity(docId = docId1, headingPath = "", text = "3", embedding = byteArrayOf())
        ))

        val all = chunkDao.getAll()
        assertEquals(3, all.size)

        val batch1 = chunkDao.getBatchSync(0, 2)
        assertEquals(2, batch1.size)
        assertEquals(ids[0], batch1[0].id)
        assertEquals(ids[1], batch1[1].id)

        val batch2 = chunkDao.getBatchSync(ids[1], 2)
        assertEquals(1, batch2.size)
        assertEquals(ids[2], batch2[0].id)
    }

    @Test
    fun getAllByTreeAndObserveCount() = runBlocking {
        chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "", text = "1", embedding = byteArrayOf()),
            ChunkEntity(docId = docId2, headingPath = "", text = "2", embedding = byteArrayOf())
        ))

        val chunks = chunkDao.getAllByTree("content://tree/1")
        assertEquals(2, chunks.size)

        val countFlow = chunkDao.observeCountByTree("content://tree/1")
        assertEquals(2, countFlow.first())
    }

    @Test
    fun getByScope() = runBlocking {
        chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "", text = "1", embedding = byteArrayOf()),
            ChunkEntity(docId = docId2, headingPath = "", text = "2", embedding = byteArrayOf())
        ))

        val folder1Chunks = chunkDao.getByScope("content://tree/1", "folder1")
        assertEquals(1, folder1Chunks.size)
        assertEquals(docId1, folder1Chunks[0].docId)
    }

    @Test
    fun deleteByDocAndIds() = runBlocking {
        chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "", text = "1", embedding = byteArrayOf()),
            ChunkEntity(docId = docId2, headingPath = "", text = "2", embedding = byteArrayOf())
        ))

        chunkDao.deleteByDoc(docId1)
        assertEquals(1, chunkDao.count())
        assertEquals(docId2, chunkDao.getAll()[0].docId)

        chunkDao.deleteByDocIds(listOf(docId2))
        assertEquals(0, chunkDao.count())
    }

    @Test
    fun deleteAllByTree() = runBlocking {
        chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "", text = "1", embedding = byteArrayOf()),
            ChunkEntity(docId = docId2, headingPath = "", text = "2", embedding = byteArrayOf())
        ))

        chunkDao.deleteAllByTree("content://tree/1")
        assertEquals(0, chunkDao.count())
    }

    @Test
    fun bm25Search() = runBlocking {
        val ids = chunkDao.insertAll(listOf(
            ChunkEntity(docId = docId1, headingPath = "Title", text = "apple banana", embedding = byteArrayOf()),
            ChunkEntity(docId = docId1, headingPath = "Title", text = "orange grape", embedding = byteArrayOf())
        ))

        // Manually insert into FTS table since there's no trigger in the test DB
        val stmt = db.openHelper.writableDatabase.compileStatement("INSERT INTO chunks_fts(rowid, text_bigram, heading_bigram) VALUES (?, ?, ?)")
        stmt.bindLong(1, ids[0])
        stmt.bindString(2, "apple banana")
        stmt.bindString(3, "Title")
        stmt.executeInsert()

        stmt.bindLong(1, ids[1])
        stmt.bindString(2, "orange grape")
        stmt.bindString(3, "Title")
        stmt.executeInsert()

        val results = chunkDao.bm25Search("apple", 10)
        assertEquals(1, results.size)
        assertEquals(ids[0], results[0].id)

        val resultsByTree = chunkDao.bm25SearchByTree("orange", "content://tree/1", 10)
        assertEquals(1, resultsByTree.size)
        assertEquals(ids[1], resultsByTree[0].id)
    }
}
