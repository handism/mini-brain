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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        io.mockk.coEvery { documentDao.getAllByTree(any()) } returns emptyList()
        io.mockk.coEvery { folderEmbeddingDao.replaceAllByTree(any(), any()) } returns Unit

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
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns listOf(mdFile)

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

        io.mockk.coEvery { chunkDao.insertAll(any<List<com.minibrain.data.db.entities.ChunkEntity>>()) } answers {
            val arg = firstArg<List<com.minibrain.data.db.entities.ChunkEntity>>()
            List(arg.size) { (it + 1).toLong() }
        }
        io.mockk.every { writableDb.execSQL(any(), any<Array<Any?>>()) } returns Unit

        io.mockk.coEvery { folderEmbeddingDao.upsertAll(any()) } returns Unit

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
    fun testIndexFolder_insertNewDocsException_closesStatement() = kotlinx.coroutines.test.runTest {
        val treeUriStr = "content://tree/uri"

        io.mockk.mockkStatic(android.net.Uri::class)
        val treeUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(treeUriStr) } returns treeUri
        io.mockk.every { treeUri.toString() } returns treeUriStr

        val fileUriStr = "content://tree/uri/new.md"
        val fileUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(fileUriStr) } returns fileUri
        io.mockk.every { fileUri.toString() } returns fileUriStr

        io.mockk.mockkObject(com.minibrain.data.md.MdFileReader)
        val mdFile = io.mockk.mockk<com.minibrain.data.md.MdFile>(relaxed = true) {
            io.mockk.every { uri } returns fileUri
            io.mockk.every { name } returns "new.md"
            io.mockk.every { relativePath } returns "new.md"
            io.mockk.every { lastModified } returns 0L
            io.mockk.every { contentHash } returns "hash_new"
            io.mockk.every { content } returns "# New Content"
        }
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns listOf(mdFile)

        io.mockk.coEvery { documentDao.getByFileUris(any()) } returns emptyList()
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()

        io.mockk.coEvery { documentDao.insertAll(any()) } throws RuntimeException("Insert docs failed")

        val stmt = io.mockk.mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        io.mockk.every { writableDb.compileStatement(any()) } returns stmt

        try {
            repository.indexFolder(treeUri)
            org.junit.Assert.assertEquals(IndexingState.Error("Insert docs failed"), repository.indexingState.value)
        } finally {
            io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
            io.mockk.unmockkStatic(android.net.Uri::class)
        }

        io.mockk.verify(exactly = 1) { stmt.close() }
    }

    @org.junit.Test
    fun testIndexFolder_refreshMetadataException_closesStatement() = kotlinx.coroutines.test.runTest {
        val treeUriStr = "content://tree/uri"

        io.mockk.mockkStatic(android.net.Uri::class)
        val treeUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(treeUriStr) } returns treeUri
        io.mockk.every { treeUri.toString() } returns treeUriStr

        val fileUriStr = "content://tree/uri/update.md"
        val fileUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(fileUriStr) } returns fileUri
        io.mockk.every { fileUri.toString() } returns fileUriStr

        io.mockk.mockkObject(com.minibrain.data.md.MdFileReader)
        val mdFile = com.minibrain.data.md.MdFile(
            uri = fileUri,
            name = "update.md",
            relativePath = "update.md",
            lastModified = 0L,
            contentHash = "hash_same",
            content = "# Existing Content"
        )
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns listOf(mdFile)

        io.mockk.mockkObject(com.minibrain.data.md.MarkdownMetaExtractor)
        io.mockk.every { com.minibrain.data.md.MarkdownMetaExtractor.extractHeadings(any()) } returns emptyList()
        io.mockk.every { com.minibrain.data.md.MarkdownMetaExtractor.extractFirstParagraph(any()) } returns ""
        io.mockk.every { com.minibrain.data.md.MarkdownMetaExtractor.extractTags(any()) } returns emptyList()
        io.mockk.every { com.minibrain.data.md.MarkdownMetaExtractor.extractDateFromContent(any()) } returns null

        val docEntity = io.mockk.mockk<com.minibrain.data.db.entities.DocumentEntity>(relaxed = true) {
            io.mockk.every { id } returns 1L
            io.mockk.every { this@mockk.treeUri } returns treeUriStr
            io.mockk.every { this@mockk.fileUri } returns fileUriStr
            io.mockk.every { fileName } returns "update.md"
            io.mockk.every { relativePath } returns "update.md"
            io.mockk.every { lastModified } returns 0L
            io.mockk.every { contentHash } returns "hash_same"
            io.mockk.every { headings } returns null // Triggers update
            io.mockk.every { copy(
                headings = any(),
                firstParagraph = any(),
                tags = any(),
                documentDate = any()
            ) } returns this
        }
        io.mockk.coEvery { documentDao.getAllByTree(treeUriStr) } returns listOf(docEntity)
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns listOf(
            com.minibrain.data.db.daos.DocChunkCount(1L, 5)
        )

        // Force exception in refreshMetadata
        io.mockk.coEvery { documentDao.updateAll(any()) } throws RuntimeException("Refresh metadata failed")

        val stmt = io.mockk.mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        io.mockk.every { writableDb.compileStatement(any()) } returns stmt

        try {
            repository.indexFolder(treeUri)
            org.junit.Assert.assertEquals(IndexingState.Error("Refresh metadata failed"), repository.indexingState.value)
        } finally {
            io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
            io.mockk.unmockkObject(com.minibrain.data.md.MarkdownMetaExtractor)
            io.mockk.unmockkStatic(android.net.Uri::class)
        }

        io.mockk.verify(exactly = 1) { stmt.close() }
    }

    @org.junit.Test
    fun testIndexFolder_chunkerException_skipsDocAndContinues() = kotlinx.coroutines.test.runTest {
        val treeUriStr = "content://tree/uri"

        io.mockk.mockkStatic(android.net.Uri::class)
        val treeUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(treeUriStr) } returns treeUri
        io.mockk.every { treeUri.toString() } returns treeUriStr

        val fileUriStr1 = "content://tree/uri/file1.md"
        val fileUri1 = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(fileUriStr1) } returns fileUri1
        io.mockk.every { fileUri1.toString() } returns fileUriStr1

        val fileUriStr2 = "content://tree/uri/file2.md"
        val fileUri2 = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(fileUriStr2) } returns fileUri2
        io.mockk.every { fileUri2.toString() } returns fileUriStr2

        io.mockk.mockkObject(com.minibrain.data.md.MdFileReader)
        val mdFile1 = com.minibrain.data.md.MdFile(
            uri = fileUri1,
            name = "file1.md",
            relativePath = "file1.md",
            lastModified = 0L,
            contentHash = "hash1",
            content = "# Malformed chunking text"
        )
        val mdFile2 = com.minibrain.data.md.MdFile(
            uri = fileUri2,
            name = "file2.md",
            relativePath = "file2.md",
            lastModified = 0L,
            contentHash = "hash2",
            content = "# Valid chunking text"
        )
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns listOf(mdFile1, mdFile2)

        io.mockk.coEvery { documentDao.getAllByTree(treeUriStr) } returns emptyList()
        io.mockk.coEvery { documentDao.getByFileUris(any()) } returns emptyList()
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()
        io.mockk.coEvery { documentDao.insertAll(any()) } answers { val arg = firstArg<List<Any>>(); List(arg.size) { (it + 1).toLong() } }

        io.mockk.mockkObject(com.minibrain.data.md.MarkdownChunker)
        io.mockk.every { com.minibrain.data.md.MarkdownChunker.chunk(any(), "file1.md") } throws RuntimeException("Chunking failed")
        io.mockk.every { com.minibrain.data.md.MarkdownChunker.chunk(any(), "file2.md") } returns listOf(com.minibrain.data.md.Chunk("path", "Valid chunking text"))
        io.mockk.coEvery { embedder.embed(any(), any()) } returns floatArrayOf(0.1f, 0.2f)
        io.mockk.coEvery { chunkDao.insertAll(any<List<com.minibrain.data.db.entities.ChunkEntity>>()) } answers { val arg = firstArg<List<Any>>(); List(arg.size) { (it + 1).toLong() } }

        io.mockk.every { writableDb.execSQL(any(), any<Array<Any?>>()) } returns Unit
        io.mockk.every { writableDb.compileStatement(any()) } returns io.mockk.mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        io.mockk.every { writableDb.beginTransaction() } returns Unit
        io.mockk.every { writableDb.setTransactionSuccessful() } returns Unit
        io.mockk.every { writableDb.endTransaction() } returns Unit
        io.mockk.coEvery { folderEmbeddingDao.upsertAll(any()) } returns Unit

        try {
            repository.indexFolder(treeUri)
            // Verify chunk insertion happened for the valid file but not the malformed one
            io.mockk.coVerify(exactly = 1) { chunkDao.insertAll(match { it.size == 1 && it[0].text == "Valid chunking text" }) }
        } finally {
            io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
            io.mockk.unmockkObject(com.minibrain.data.md.MarkdownChunker)
            io.mockk.unmockkStatic(android.net.Uri::class)
        }
    }

    @org.junit.Test
    fun testIndexFolder_batchInsertException_rollsBack() = kotlinx.coroutines.test.runTest {
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
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns listOf(mdFile)

        io.mockk.coEvery { documentDao.getAllByTree(treeUriStr) } returns emptyList()
        io.mockk.coEvery { documentDao.getByFileUris(any()) } returns emptyList()
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()
        io.mockk.coEvery { documentDao.insertAll(any()) } answers { val arg = firstArg<List<Any>>(); List(arg.size) { (it + 1).toLong() } }

        io.mockk.coEvery { embedder.embed(any(), any()) } returns floatArrayOf(0.1f, 0.2f)

        io.mockk.coEvery { chunkDao.insertAll(any<List<com.minibrain.data.db.entities.ChunkEntity>>()) } answers { val arg = firstArg<List<Any>>(); List(arg.size) { (it + 1).toLong() } }

        // Force an exception during FTS insertion
        val stmt = io.mockk.mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        io.mockk.every { writableDb.compileStatement(any()) } returns stmt
        io.mockk.every { stmt.executeInsert() } throws RuntimeException("DB batch insertion failed")

        repository.indexFolder(treeUri)
        org.junit.Assert.assertEquals(IndexingState.Error("DB batch insertion failed"), repository.indexingState.value)

        io.mockk.verify { writableDb.beginTransaction() }
        io.mockk.verify(exactly = 0) { writableDb.setTransactionSuccessful() }
        io.mockk.verify { writableDb.endTransaction() }
        io.mockk.verify { stmt.close() }

        io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
        io.mockk.unmockkStatic(android.net.Uri::class)
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
        io.mockk.coEvery { chunkDao.deleteFtsByTree(any()) } returns Unit

        // Mock DAOs
        io.mockk.coEvery { chunkDao.deleteAllByTree(any()) } returns Unit
        io.mockk.coEvery { documentDao.deleteAllByTree(any()) } returns Unit
        io.mockk.coEvery { folderEmbeddingDao.deleteAllByTree(any()) } returns Unit

        repository.clearFolder(treeUri)

        io.mockk.coVerify { chunkDao.deleteFtsByTree(treeUri) }
        io.mockk.coVerify { chunkDao.deleteAllByTree(treeUri) }
        io.mockk.coVerify { documentDao.deleteAllByTree(treeUri) }
        io.mockk.coVerify { folderEmbeddingDao.deleteAllByTree(treeUri) }

        // Verify state is Idle
        org.junit.Assert.assertEquals(com.minibrain.data.repo.IndexingState.Idle, repository.indexingState.value)
    }

    @org.junit.Test
    fun observeDocCount_returnsFlowFromDao() = kotlinx.coroutines.test.runTest {
        val treeUri = "content://test-tree-uri"
        val expectedFlow = flowOf(0, 5, 10)
        io.mockk.every { documentDao.observeCountByTree(treeUri) } returns expectedFlow

        val resultFlow = repository.observeDocCount(treeUri)
        val resultList = resultFlow.toList()

        org.junit.Assert.assertEquals(listOf(0, 5, 10), resultList)
        io.mockk.verify(exactly = 1) { documentDao.observeCountByTree(treeUri) }
    }

    @org.junit.Test
    fun observeChunkCount_returnsFlowFromDao() = kotlinx.coroutines.test.runTest {
        val treeUri = "content://test-tree-uri"
        val expectedFlow = flowOf(0, 50, 100)
        io.mockk.every { chunkDao.observeCountByTree(treeUri) } returns expectedFlow

        val resultFlow = repository.observeChunkCount(treeUri)
        val resultList = resultFlow.toList()

        org.junit.Assert.assertEquals(listOf(0, 50, 100), resultList)
        io.mockk.verify(exactly = 1) { chunkDao.observeCountByTree(treeUri) }
    }

    @org.junit.Test
    fun testDeleteOldDocs_exceptionInTransaction_rollsBack_viaIndexFolder() = kotlinx.coroutines.test.runTest {
        val treeUriStr = "content://tree/uri"

        io.mockk.mockkStatic(android.net.Uri::class)
        val treeUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(treeUriStr) } returns treeUri
        io.mockk.every { treeUri.toString() } returns treeUriStr

        val fileUriStr = "content://tree/uri/old.md"
        val fileUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { android.net.Uri.parse(fileUriStr) } returns fileUri
        io.mockk.every { fileUri.toString() } returns fileUriStr

        io.mockk.mockkObject(com.minibrain.data.md.MdFileReader)
        val mdFile = io.mockk.mockk<com.minibrain.data.md.MdFile>(relaxed = true) {
            io.mockk.every { uri } returns fileUri
            io.mockk.every { name } returns "old.md"
            io.mockk.every { relativePath } returns "old.md"
            io.mockk.every { lastModified } returns 0L
            io.mockk.every { contentHash } returns "hash_new"
            io.mockk.every { content } returns "# New Content"
        }
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns listOf(mdFile)

        val docEntity = io.mockk.mockk<com.minibrain.data.db.entities.DocumentEntity>(relaxed = true) {
            io.mockk.every { id } returns 1L
            io.mockk.every { this@mockk.treeUri } returns treeUriStr
            io.mockk.every { this@mockk.fileUri } returns fileUriStr
            io.mockk.every { fileName } returns "old.md"
            io.mockk.every { relativePath } returns "old.md"
            io.mockk.every { lastModified } returns 0L
            io.mockk.every { contentHash } returns "hash_old"
        }
        io.mockk.coEvery { documentDao.getAllByTree(treeUriStr) } returns listOf(docEntity)
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()

        io.mockk.coEvery { chunkDao.deleteFtsByDocIds(any()) } throws RuntimeException("Delete FTS failed")

        val stmt = io.mockk.mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        io.mockk.every { writableDb.compileStatement(any()) } returns stmt

        io.mockk.every { writableDb.beginTransaction() } returns Unit
        io.mockk.every { writableDb.setTransactionSuccessful() } returns Unit
        io.mockk.every { writableDb.endTransaction() } returns Unit
        io.mockk.coEvery { documentDao.updateAll(any()) } returns Unit

        try {
            repository.indexFolder(treeUri)
            org.junit.Assert.assertEquals(IndexingState.Error("Delete FTS failed"), repository.indexingState.value)
        } finally {
            io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
            io.mockk.unmockkStatic(android.net.Uri::class)
        }

        io.mockk.verify(exactly = 1) { writableDb.beginTransaction() }
        io.mockk.verify(exactly = 0) { writableDb.setTransactionSuccessful() }
        io.mockk.verify(exactly = 1) { writableDb.endTransaction() }
        io.mockk.verify(exactly = 1) { stmt.close() }
    }

    @org.junit.Test
    fun testIndexFolder_removedFile_deletesDocChunksAndFts() = kotlinx.coroutines.test.runTest {
        val treeUriStr = "content://tree/uri"

        io.mockk.mockkStatic(android.net.Uri::class)
        val treeUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { treeUri.toString() } returns treeUriStr

        // フォルダには何も残っていない
        io.mockk.mockkObject(com.minibrain.data.md.MdFileReader)
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns emptyList()

        val removedDoc = io.mockk.mockk<com.minibrain.data.db.entities.DocumentEntity>(relaxed = true) {
            io.mockk.every { id } returns 42L
            io.mockk.every { fileUri } returns "content://tree/uri/gone.md"
        }
        io.mockk.coEvery { documentDao.getAllByTree(treeUriStr) } returns listOf(removedDoc)
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()
        io.mockk.coEvery { chunkDao.deleteFtsByDocIds(any()) } returns Unit
        io.mockk.coEvery { chunkDao.deleteByDocIds(any()) } returns Unit
        io.mockk.coEvery { documentDao.deleteByIds(any()) } returns Unit
        io.mockk.every { writableDb.compileStatement(any()) } returns io.mockk.mockk(relaxed = true)

        try {
            repository.indexFolder(treeUri)
        } finally {
            io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
            io.mockk.unmockkStatic(android.net.Uri::class)
        }

        io.mockk.coVerifyOrder {
            chunkDao.deleteFtsByDocIds(listOf(42L))
            chunkDao.deleteByDocIds(listOf(42L))
            documentDao.deleteByIds(listOf(42L))
        }
        io.mockk.verify { writableDb.setTransactionSuccessful() }
        // フォルダが 1 つも無ければ folder_embeddings も空に入れ替わる
        io.mockk.coVerify { folderEmbeddingDao.replaceAllByTree(treeUriStr, emptyList()) }
    }

    @org.junit.Test
    fun testIndexFolder_usesBatchEmbedding() = kotlinx.coroutines.test.runTest {
        val treeUriStr = "content://tree/uri"

        io.mockk.mockkStatic(android.net.Uri::class)
        val treeUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { treeUri.toString() } returns treeUriStr
        val fileUri = io.mockk.mockk<android.net.Uri>()
        io.mockk.every { fileUri.toString() } returns "content://tree/uri/file1.md"

        io.mockk.mockkObject(com.minibrain.data.md.MdFileReader)
        val mdFile = com.minibrain.data.md.MdFile(
            uri = fileUri,
            name = "file1.md",
            relativePath = "file1.md",
            lastModified = 0L,
            contentHash = "hash1",
            content = "# a",
        )
        io.mockk.coEvery { com.minibrain.data.md.MdFileReader.listMdFiles(any<android.content.Context>(), any<android.net.Uri>(), any(), any()) } returns listOf(mdFile)
        io.mockk.mockkObject(com.minibrain.data.md.MarkdownChunker)
        io.mockk.every { com.minibrain.data.md.MarkdownChunker.chunk(any(), any()) } returns List(3) {
            com.minibrain.data.md.Chunk("h$it", "text$it")
        }

        io.mockk.coEvery { documentDao.getByFileUris(any()) } returns emptyList()
        io.mockk.coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()
        io.mockk.coEvery { documentDao.insertAll(any()) } returns listOf(1L)
        io.mockk.coEvery { embedder.embedAll(any(), any()) } answers { firstArg<List<String>>().map { floatArrayOf(0.1f) } }
        io.mockk.coEvery { chunkDao.insertAll(any<List<com.minibrain.data.db.entities.ChunkEntity>>()) } answers {
            List(firstArg<List<Any>>().size) { (it + 1).toLong() }
        }
        io.mockk.every { writableDb.compileStatement(any()) } returns io.mockk.mockk(relaxed = true)

        try {
            repository.indexFolder(treeUri)
        } finally {
            io.mockk.unmockkObject(com.minibrain.data.md.MdFileReader)
            io.mockk.unmockkObject(com.minibrain.data.md.MarkdownChunker)
            io.mockk.unmockkStatic(android.net.Uri::class)
        }

        io.mockk.coVerify(exactly = 1) { embedder.embedAll(listOf("h0\ntext0", "h1\ntext1", "h2\ntext2"), any()) }
        io.mockk.coVerify(exactly = 0) { embedder.embed(any(), any()) }
        io.mockk.coVerify { chunkDao.insertAll(match<List<com.minibrain.data.db.entities.ChunkEntity>> { it.size == 3 }) }
    }

    @org.junit.Test
    fun testEnsureFtsIndex_mismatch_clearsFtsBeforeRebuild() = kotlinx.coroutines.test.runTest {
        io.mockk.coEvery { chunkDao.count() } returns 1
        io.mockk.every { readableDb.query(any<String>(), any<Array<Any?>>()) } returns cursor
        io.mockk.every { cursor.moveToFirst() } returns true
        io.mockk.every { cursor.getInt(0) } returns 3 // 孤立 FTS 行がある
        io.mockk.every { cursor.close() } returns Unit
        io.mockk.every { chunkDao.getBatchSync(any(), any()) } returns emptyList()
        io.mockk.every { writableDb.compileStatement(any()) } returns io.mockk.mockk(relaxed = true)

        repository.ensureFtsIndex()

        io.mockk.verifyOrder {
            writableDb.beginTransaction()
            writableDb.execSQL("DELETE FROM chunks_fts")
            writableDb.setTransactionSuccessful()
        }
    }
}
