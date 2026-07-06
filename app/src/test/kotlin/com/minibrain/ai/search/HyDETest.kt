package com.minibrain.ai.search

import com.minibrain.ai.llm.LlmService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class HyDETest {

    private lateinit var llmService: LlmService
    private lateinit var hyDE: HyDE

    @Before
    fun setup() {
        llmService = mockk()
        hyDE = HyDE(llmService)

        // Plant a dummy tree to intercept Timber calls without MockK signature issues
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // No-op
            }
        })
    }

    @After
    fun teardown() {
        Timber.uprootAll()
    }

    @Test
    fun `returns null when llmService is not ready`() = runTest {
        every { llmService.isReady() } returns false
        val result = hyDE.generateHypothetical("test query")
        assertNull(result)
    }

    @Test
    fun `returns generated string when successful`() = runTest {
        every { llmService.isReady() } returns true
        val generatedTokens = listOf("This is a ", "hypothetical ", "document.")
        every { llmService.generateStream(any()) } returns flowOf(*generatedTokens.toTypedArray())

        val result = hyDE.generateHypothetical("test query")
        assertEquals("This is a hypothetical document.", result)
    }

    @Test
    fun `returns null when exception occurs during generation`() = runTest {
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flow {
            emit("Start... ")
            throw RuntimeException("Generation failed")
        }

        val result = hyDE.generateHypothetical("test query")
        assertNull(result)
    }

    @Test
    fun `limits output to MAX_CHARS`() = runTest {
        every { llmService.isReady() } returns true
        // MAX_CHARS is 280. Create a string that is 300 chars long.
        val longString = "a".repeat(300)
        every { llmService.generateStream(any()) } returns flowOf(longString)

        val result = hyDE.generateHypothetical("test query")
        assertEquals(280, result?.length)
        assertEquals("a".repeat(280), result)
    }

    @Test
    fun `returns null when generated string is blank`() = runTest {
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flowOf("   \n  \t  ")

        val result = hyDE.generateHypothetical("test query")
        assertNull(result)
    }

    @Test
    fun `handles timeouts correctly`() = runTest {
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flow {
            delay(7_000L) // Exceeds GENERATE_TIMEOUT_MS (6_000L)
            emit("Late response")
        }

        val result = hyDE.generateHypothetical("test query")
        assertNull(result)
    }
}
