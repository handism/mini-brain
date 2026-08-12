package com.minibrain.ai.search

import com.minibrain.ai.llm.LlmService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

class QueryExpanderTest {

    private lateinit var llmService: LlmService
    private lateinit var expander: QueryExpander

    @Before
    fun setup() {
        llmService = mockk()
        expander = QueryExpander(llmService)

        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {}
        })
    }

    @After
    fun teardown() {
        Timber.uprootAll()
    }

    @Test
    fun `expand returns original query when llmService is not ready`() = runTest {
        every { llmService.isReady() } returns false
        val result = expander.expand("test query")
        assertEquals(listOf("test query"), result)
    }

    @Test
    fun `expand returns parsed queries including original query when successful`() = runTest {
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flowOf("""["test query", "expanded query 1", "expanded query 2"]""")

        val result = expander.expand("test query")
        assertEquals(listOf("test query", "expanded query 1", "expanded query 2"), result)
    }

    @Test
    fun `expand prepends original query if not present in LLM response`() = runTest {
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flowOf("""["expanded 1", "expanded 2"]""")

        val result = expander.expand("original query")
        assertEquals(listOf("original query", "expanded 1", "expanded 2"), result)
    }

    @Test
    fun `expand returns original query when LLM stream throws exception`() = runTest {
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flow {
            throw RuntimeException("Stream failure")
        }

        val result = expander.expand("test query")
        assertEquals(listOf("test query"), result)
    }

    @Test
    fun `parseJsonArray parses valid JSON array`() {
        val raw = """["apple", "banana", "cherry"]"""
        assertEquals(listOf("apple", "banana", "cherry"), QueryExpander.parseJsonArray(raw))
    }

    @Test
    fun `parseJsonArray returns empty list for empty JSON array`() {
        val raw = "[]"
        assertTrue(QueryExpander.parseJsonArray(raw).isEmpty())
    }

    @Test
    fun `parseJsonArray filters out blank or empty strings`() {
        val raw = """["", "  ", "valid", ""]"""
        assertEquals(listOf("valid"), QueryExpander.parseJsonArray(raw))
    }

    @Test
    fun `parseJsonArray handles invalid JSON format gracefully`() {
        // Missing closing quote for the first string, in the current implementation
        // the regex matches "missing_quote, "
        val raw = """["missing_quote, "valid"]"""
        assertEquals(listOf("missing_quote, "), QueryExpander.parseJsonArray(raw))
    }

    @Test
    fun `parseJsonArray returns empty list for empty string`() {
        assertTrue(QueryExpander.parseJsonArray("").isEmpty())
    }

    @Test
    fun `parseJsonArray returns empty list for string without brackets`() {
        val raw = "just a plain string without brackets"
        assertTrue(QueryExpander.parseJsonArray(raw).isEmpty())
    }
}
