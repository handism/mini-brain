package com.minibrain.data.repo

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.minibrain.ai.embed.EmbedType
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.data.db.AppDatabase
import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.daos.DocumentDao
import com.minibrain.data.db.daos.FolderEmbeddingDao
import com.minibrain.data.md.MdFile
import com.minibrain.data.md.MdFileReader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentRepositoryIndexBenchmarkTest {

    @Test
    fun benchmarkIndexing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val documentDao = mockk<DocumentDao>(relaxed = true)
        val chunkDao = mockk<ChunkDao>(relaxed = true)
        val embedder = mockk<EmbedderService>()
        val db = mockk<AppDatabase>(relaxed = true)
        val folderEmbeddingDao = mockk<FolderEmbeddingDao>(relaxed = true)

        coEvery { embedder.embed(any(), any()) } returns FloatArray(384) { 0.1f }

        val dummyMdFiles = (1..100).map {
            MdFile(
                uri = Uri.parse("file://test/$it.md"),
                name = "$it.md",
                relativePath = "$it.md",
                lastModified = 0L,
                content = "# Heading\nSome content $it. " + "Lots of words to make it chunk. ".repeat(10),
                contentHash = "hash$it"
            )
        }

        mockkObject(MdFileReader)
        coEvery { MdFileReader.listMdFiles(any(), any()) } returns dummyMdFiles

        coEvery { documentDao.getByFileUris(any()) } returns emptyList()
        coEvery { chunkDao.getChunkCountsGroupedByDoc() } returns emptyList()
        coEvery { documentDao.insert(any()) } returns 1L
        coEvery { documentDao.insertAll(any()) } returns (1..100).map { it.toLong() }
        coEvery { chunkDao.insertAll(any()) } returns listOf(1L)

        val stmt = mockk<androidx.sqlite.db.SupportSQLiteStatement>(relaxed = true)
        every { db.openHelper.writableDatabase.compileStatement(any()) } returns stmt
        val writableDb = mockk<androidx.sqlite.db.SupportSQLiteDatabase>(relaxed = true)
        every { db.openHelper.writableDatabase } returns writableDb
        every { writableDb.compileStatement(any()) } returns stmt

        val repo = DocumentRepository(
            context, documentDao, chunkDao, embedder, db, folderEmbeddingDao
        )

        val start = System.currentTimeMillis()
        repo.indexFolder(Uri.parse("file://test"))
        val time1 = System.currentTimeMillis() - start

        println("Indexing 100 files took $time1 ms")
    }
}
