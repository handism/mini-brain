package com.minibrain.data.search

import com.minibrain.data.db.daos.ChunkDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class Bm25SearchTest {

    private val testTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // Do nothing
        }
    }

    @Before
    fun setup() {
        Timber.plant(testTree)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun bm25SearchOrEmpty_returnsEmptyListOnException() = runTest {
        val chunkDao = mockk<ChunkDao>()
        // Mock to throw exception on search
        coEvery { chunkDao.bm25SearchByTree(any(), any(), any()) } throws RuntimeException("DB error")

        val result = chunkDao.bm25SearchOrEmpty("test query", "content://tree", 10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun bm25SearchOrEmpty_returnsEmptyListOnTooShortQuery() = runTest {
        val chunkDao = mockk<ChunkDao>()
        // Query "a" generates empty tokens and NGramTokenizer.toFtsMatchQuery returns null
        val result = chunkDao.bm25SearchOrEmpty("a", "content://tree", 10)
        assertTrue(result.isEmpty())
    }
}
