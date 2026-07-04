package com.minibrain.ai.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryExpanderTest {

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
