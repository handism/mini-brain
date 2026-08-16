package com.minibrain.ai.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LlmServiceSummarizeTest {

    @Test
    fun `summarize returns up to 500 chars if not ready`() = runBlocking {
        val service = LlmService()
        assertFalse(service.isReady())

        val shortText = "Short text"
        assertEquals(shortText, service.summarize(shortText))

        val longText = "a".repeat(1000)
        val result = service.summarize(longText)
        assertEquals(500, result.length)
        assertEquals("a".repeat(500), result)
    }

    @Test
    fun `summarize returns streamed text if ready`() = runBlocking {
        val service = object : LlmService() {
            override fun isReady(): Boolean = true
            override fun generateStream(prompt: String): Flow<String> = flowOf("Summary ", "result")
        }

        val longText = "a".repeat(1000)
        val result = service.summarize(longText)
        assertEquals("Summary result", result)
    }

    @Test
    fun `summarize returns fallback if stream is blank`() = runBlocking {
        val service = object : LlmService() {
            override fun isReady(): Boolean = true
            override fun generateStream(prompt: String): Flow<String> = flowOf("   ")
        }

        val longText = "a".repeat(1000)
        val result = service.summarize(longText)
        assertEquals(500, result.length)
        assertEquals("a".repeat(500), result)
    }

    @Test
    fun `summarize returns fallback if stream throws exception`() = runBlocking {
        val service = object : LlmService() {
            override fun isReady(): Boolean = true
            override fun generateStream(prompt: String): Flow<String> = flow {
                throw RuntimeException("Stream failed")
            }
        }

        val longText = "a".repeat(1000)
        val result = service.summarize(longText)
        assertEquals(500, result.length)
        assertEquals("a".repeat(500), result)
    }
}
