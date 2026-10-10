package com.minibrain.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun `stemMatchesAnyQuery returns true when query contains stem`() {
        val queries = listOf("I like apples", "What is AI?")
        assertTrue(FileNames.stemMatchesAnyQuery("AI.md", queries))
    }

    @Test
    fun `stemMatchesAnyQuery returns false when no query contains stem`() {
        val queries = listOf("What is AI?", "How about ML?")
        assertFalse(FileNames.stemMatchesAnyQuery("DL.md", queries))
    }

    @Test
    fun `stemMatchesAnyQuery is case insensitive`() {
        val queries = listOf("what is ai?")
        assertTrue(FileNames.stemMatchesAnyQuery("AI.md", queries))

        val queries2 = listOf("WHAT IS AI?")
        assertTrue(FileNames.stemMatchesAnyQuery("ai.md", queries2))
    }

    @Test
    fun `stemMatchesAnyQuery handles paths and extensions correctly`() {
        val queries = listOf("Check out my notes on android")
        assertTrue(FileNames.stemMatchesAnyQuery("folder/subfolder/Android.md", queries))
        assertTrue(FileNames.stemMatchesAnyQuery("folder/subfolder/Android.MD", queries))
    }

    @Test
    fun `stemMatchesAnyQuery returns false if stem is shorter than MIN_STEM_MATCH_CHARS`() {
        // MIN_STEM_MATCH_CHARS is 1, so empty stem should return false
        val queries = listOf("Empty string test", "")
        assertFalse(FileNames.stemMatchesAnyQuery(".md", queries))
        assertFalse(FileNames.stemMatchesAnyQuery("folder/.md", queries))
        assertFalse(FileNames.stemMatchesAnyQuery("", queries))
    }
}
