package com.minibrain.data.repo

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.daos.FolderEmbeddingDao
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import org.junit.Before

class DocumentRepositoryTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var documentDao: DocumentDao

    @MockK
    private lateinit var chunkDao: ChunkDao

    @MockK
    private lateinit var embedder: EmbedderService

    @MockK
    private lateinit var db: AppDatabase

    @MockK
    private lateinit var folderEmbeddingDao: FolderEmbeddingDao

    @MockK
    private lateinit var openHelper: SupportSQLiteOpenHelper

    @RelaxedMockK
    private lateinit var writableDb: SupportSQLiteDatabase

    @RelaxedMockK
    private lateinit var readableDb: SupportSQLiteDatabase

    @MockK
    private lateinit var cursor: Cursor

    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        io.mockk.every { db.openHelper } returns openHelper
        io.mockk.every { openHelper.writableDatabase } returns writableDb
        io.mockk.every { openHelper.readableDatabase } returns readableDb

        repository = DocumentRepository(
            context = context,
            documentDao = documentDao,
            chunkDao = chunkDao,
            embedder = embedder,
            db = db,
            folderEmbeddingDao = folderEmbeddingDao
        )
    }

    @org.junit.Test
    fun testEnsureFtsIndex_dbException_rollsBack() = kotlinx.coroutines.test.runTest {
        io.mockk.coEvery { chunkDao.count() } returns 1
        io.mockk.every { readableDb.query(any<String>(), any<Array<Any?>>()) } returns cursor
        io.mockk.every { cursor.moveToFirst() } returns true
        io.mockk.every { cursor.getInt(0) } returns 0 // ftsCount == 0, triggering index update
        io.mockk.every { cursor.close() } returns Unit

        val chunkEntity = com.minibrain.data.db.entities.ChunkEntity(
            id = 1,
            docId = 1,
            headingPath = "Heading",
            text = "Chunk Text",
            embedding = ByteArray(0)
        )
        io.mockk.every { chunkDao.getBatchSync(any(), any()) } returns listOf(chunkEntity) andThen emptyList()

        // Force an exception during insertion
        val stmt = io.mockk.mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        io.mockk.every { writableDb.compileStatement(any()) } returns stmt
        io.mockk.every { stmt.executeInsert() } throws RuntimeException("DB insertion failed")

        try {
            repository.ensureFtsIndex()
            org.junit.Assert.fail("Expected exception")
        } catch (e: Exception) {
            org.junit.Assert.assertEquals("DB insertion failed", e.message)
        }

        io.mockk.verify { writableDb.beginTransaction() }
        io.mockk.verify(exactly = 0) { writableDb.setTransactionSuccessful() }
        io.mockk.verify { writableDb.endTransaction() }
    }

    @org.junit.Test
    fun testEnsureFtsIndex_getBatchSyncException_rollsBack() = kotlinx.coroutines.test.runTest {
        io.mockk.coEvery { chunkDao.count() } returns 1
        io.mockk.every { readableDb.query(any<String>(), any<Array<Any?>>()) } returns cursor
        io.mockk.every { cursor.moveToFirst() } returns true
        io.mockk.every { cursor.getInt(0) } returns 0 // ftsCount == 0, triggering index update
        io.mockk.every { cursor.close() } returns Unit

        // Force an exception during batch retrieval
        io.mockk.every { chunkDao.getBatchSync(any(), any()) } throws RuntimeException("Batch retrieval failed")

        val stmt = io.mockk.mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        io.mockk.every { writableDb.compileStatement(any()) } returns stmt

        try {
            repository.ensureFtsIndex()
            org.junit.Assert.fail("Expected exception")
        } catch (e: Exception) {
            org.junit.Assert.assertEquals("Batch retrieval failed", e.message)
        }

        io.mockk.verify { writableDb.beginTransaction() }
        io.mockk.verify(exactly = 0) { writableDb.setTransactionSuccessful() }
        io.mockk.verify { stmt.close() }
        io.mockk.verify { writableDb.endTransaction() }
    }

    @org.junit.After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    @org.junit.Test
    fun testIndexFolder_embedderException_skipsChunkAndContinues() = kotlinx.coroutines.test.runTest {
        val treeUriStr = "content://tree/uri"

        io.mockk.mockkStatic(android.net.Uri::class)
        val treeUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(treeUriStr) } returns treeUri
        io.mockk.every { treeUri.toString() } returns treeUriStr

        val fileUriStr = "content://tree/uri/file1.md"
        val fileUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(fileUriStr) } returns fileUri
        io.mockk.every { fileUri.toString() } returns fileUriStr

        io.mockk.mockkObject(com.minibrain.data.md.MdFileReader)
        val mdFile = com.minibrain.data.md.MdFile(
            uri = fileUri,
            name = "file1.md",
            relativePath = "file1.md",
            lastModified = 0L,
            contentHash = "hash1",
            content = "# Heading 1\nThis is paragraph 1.\n# Heading 2\nThis is paragraph 2."
        )
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any(), any()) } returns listOf(mdFile)

        io.mockk.coEvery { documentDao.getAllByTree(treeUriStr) } returns emptyList()
        io.mockk.coEvery { documentDao.getByFileUris(any()) } returns emptyList()
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()
        io.mockk.coEvery { documentDao.insert(any()) } returns 1L
        io.mockk.coEvery { documentDao.insertAll(any()) } answers { val arg = firstArg<List<Any>>(); List(arg.size) { (it + 1).toLong() } }

        // Log is now Timber, no mock needed as it no-ops without a planted Tree

        // The md chunker splits into multiple chunks.
        // We throw an exception on the first embed call, but normal on the subsequent ones
        var embedCallCount = 0
        io.mockk.coEvery { embedder.embed(any(), any()) } answers {
            embedCallCount++
            if (embedCallCount == 1) {
                throw RuntimeException("Embedder failed")
            }
            floatArrayOf(0.1f, 0.2f)
        }

        io.mockk.coEvery { chunkDao.insertAll(any<List<com.minibrain.data.db.entities.ChunkEntity>>()) } returns listOf(2L)
        io.mockk.every { writableDb.execSQL(any(), any<Array<Any?>>()) } returns Unit

        io.mockk.coEvery { folderEmbeddingDao.upsert(any()) } returns Unit

        try {
            repository.indexFolder(treeUri)

            // It should have inserted at least one chunk even though the first failed
            io.mockk.coVerify { chunkDao.insertAll(match<List<com.minibrain.data.db.entities.ChunkEntity>> { it.isNotEmpty() }) }

        } finally {
            io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
            io.mockk.unmockkStatic(android.net.Uri::class)
        }
    }

    @org.junit.Test
    fun testFtsCount_cursorException_closesCursor() = kotlinx.coroutines.test.runTest {
        io.mockk.coEvery { chunkDao.count() } returns 1
        io.mockk.every { readableDb.query(any<String>(), any<Array<Any?>>()) } returns cursor
        io.mockk.every { cursor.moveToFirst() } returns true
        io.mockk.every { cursor.getInt(0) } throws RuntimeException("Cursor read failed")
        io.mockk.every { cursor.close() } returns Unit

        try {
            repository.ensureFtsIndex()
            org.junit.Assert.fail("Expected exception")
        } catch (e: Exception) {
            org.junit.Assert.assertEquals("Cursor read failed", e.message)
        }

        io.mockk.verify { cursor.close() }
    }

    @org.junit.Test
    fun testClearFolder_deletesFtsAndDaos_resetsState() = kotlinx.coroutines.test.runTest {
        val treeUri = "content://tree/uri"

        // Mock DB for deleteFtsByTree
        io.mockk.every { writableDb.execSQL(any<String>(), any<Array<Any?>>()) } returns Unit

        // Mock DAOs
        io.mockk.coEvery { chunkDao.deleteAllByTree(any()) } returns Unit
        io.mockk.coEvery { documentDao.deleteAllByTree(any()) } returns Unit

        repository.clearFolder(treeUri)

        // Verify FTS deletion query execution
        io.mockk.verify {
            writableDb.execSQL(
                match { it.contains("DELETE FROM chunks_fts WHERE rowid IN") },
                match { it.contentEquals(arrayOf(treeUri)) }
            )
        }

        // Verify DAO deletions
        io.mockk.coVerify { chunkDao.deleteAllByTree(treeUri) }
        io.mockk.coVerify { documentDao.deleteAllByTree(treeUri) }

        // Verify state is Idle
        org.junit.Assert.assertEquals(com.minibrain.data.repo.IndexingState.Idle, repository.indexingState.value)
    }
}
