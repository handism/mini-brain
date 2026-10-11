package com.minibrain.data.search

import com.minibrain.data.db.daos.ChunkDao
import com.minibrain.data.db.entities.ChunkEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import timber.log.Timber

class Bm25SearchTest {

    private val chunkDao = mockk<ChunkDao>()

    // Timber tree for swallowing logs in tests
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}
    }

    @Before
    fun setUp() {
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `bm25SearchOrEmpty returns empty list when query is too short`() = runTest {
        val result = chunkDao.bm25SearchOrEmpty(query = " ", treeUri = "test_uri", limit = 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `bm25SearchOrEmpty returns list of chunks on success`() = runTest {
        val chunk1 = mockk<ChunkEntity>()
        val chunk2 = mockk<ChunkEntity>()

        // The query "test" produces tokens through NGramTokenizer
        // NGramTokenizer.toFtsMatchQuery("test") -> "\"test\""

        coEvery { chunkDao.bm25SearchByTree(any(), "test_uri", 10) } returns listOf(chunk1, chunk2)

        val result = chunkDao.bm25SearchOrEmpty(query = "test", treeUri = "test_uri", limit = 10)
        assertEquals(2, result.size)
        assertEquals(listOf(chunk1, chunk2), result)
    }

    @Test
    fun `bm25SearchOrEmpty returns empty list when search throws exception`() = runTest {
        coEvery { chunkDao.bm25SearchByTree(any(), "test_uri", 10) } throws RuntimeException("SQLite Error")

        val result = chunkDao.bm25SearchOrEmpty(query = "test", treeUri = "test_uri", limit = 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `bm25SearchOrEmpty throws CancellationException when search is cancelled`() = runTest {
        coEvery { chunkDao.bm25SearchByTree(any(), "test_uri", 10) } throws CancellationException("Cancelled")

        var exceptionThrown = false
        try {
            chunkDao.bm25SearchOrEmpty(query = "test", treeUri = "test_uri", limit = 10)
        } catch (e: CancellationException) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }
}
