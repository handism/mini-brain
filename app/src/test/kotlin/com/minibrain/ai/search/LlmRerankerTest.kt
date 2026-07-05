package com.minibrain.ai.search

import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class LlmRerankerTest {

    private lateinit var llmService: LlmService
    private lateinit var reranker: LlmReranker

    @Before
    fun setup() {
        // Plant a fake Timber tree to avoid log errors during testing
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}
        })
        llmService = mockk()
        reranker = LlmReranker(llmService)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    private fun createCandidates(count: Int): List<Citation> {
        return (0 until count).map { i ->
            Citation(
                headingPath = "path_$i",
                snippet = "Snippet for item $i",
                docId = i.toLong(),
                relativePath = "relative/path_$i.md",
                source = SourceType.READ_FILE
            )
        }
    }

    @Test
    fun `rerank returns original candidates when size is less than or equal to topK`() = runTest {
        val candidates = createCandidates(2)
        val result = reranker.rerank("query", candidates, topK = 3)
        assertEquals(candidates, result)
    }

    @Test
    fun `rerank returns original candidates truncated to topK when llmService is not ready`() = runTest {
        val candidates = createCandidates(5)
        every { llmService.isReady() } returns false

        val result = reranker.rerank("query", candidates, topK = 3)

        assertEquals(3, result.size)
        assertEquals(candidates.take(3), result)
    }

    @Test
    fun `rerank returns correctly ordered candidates based on LLM output`() = runTest {
        val candidates = createCandidates(5)
        every { llmService.isReady() } returns true
        coEvery { llmService.generateStream(any()) } returns flowOf("[3, 1]")

        val result = reranker.rerank("query", candidates, topK = 2)

        assertEquals(2, result.size)
        assertEquals(candidates[3], result[0])
        assertEquals(candidates[1], result[1])
    }

    @Test
    fun `rerank supplements missing indices with original order`() = runTest {
        val candidates = createCandidates(4)
        every { llmService.isReady() } returns true
        coEvery { llmService.generateStream(any()) } returns flowOf("[2]") // Only one index, topK is 3

        val result = reranker.rerank("query", candidates, topK = 3)

        assertEquals(3, result.size)
        assertEquals(candidates[2], result[0]) // Returned by LLM
        assertEquals(candidates[0], result[1]) // Supplemented
        assertEquals(candidates[1], result[2]) // Supplemented
        // candidates[2] is skipped since it's already included, and we only need 3 elements.
    }

    @Test
    fun `rerank returns truncated original candidates when LLM returns invalid JSON`() = runTest {
        val candidates = createCandidates(4)
        every { llmService.isReady() } returns true
        coEvery { llmService.generateStream(any()) } returns flowOf("This is an invalid response.")

        val result = reranker.rerank("query", candidates, topK = 2)

        assertEquals(2, result.size)
        assertEquals(candidates.take(2), result)
    }

    @Test
    fun `rerank returns truncated original candidates when LLM returns empty array`() = runTest {
        val candidates = createCandidates(4)
        every { llmService.isReady() } returns true
        coEvery { llmService.generateStream(any()) } returns flowOf("[]")

        val result = reranker.rerank("query", candidates, topK = 2)

        assertEquals(2, result.size)
        assertEquals(candidates.take(2), result)
    }

    @Test
    fun `rerank ignores out of bounds indices`() = runTest {
        val candidates = createCandidates(3)
        every { llmService.isReady() } returns true
        // 10 is out of bounds, 2 is valid
        coEvery { llmService.generateStream(any()) } returns flowOf("[10, 2]")

        val result = reranker.rerank("query", candidates, topK = 2)

        // Only index 2 is processed, then it needs 1 more supplemented
        assertEquals(2, result.size)
        assertEquals(candidates[2], result[0])
        assertEquals(candidates[0], result[1])
    }

    @Test
    fun `rerank falls back to truncated candidates when LLM generation throws exception`() = runTest {
        val candidates = createCandidates(4)
        every { llmService.isReady() } returns true
        coEvery { llmService.generateStream(any()) } returns flow { throw RuntimeException("LLM failure") }

        val result = reranker.rerank("query", candidates, topK = 2)

        assertEquals(2, result.size)
        assertEquals(candidates.take(2), result)
    }
}
