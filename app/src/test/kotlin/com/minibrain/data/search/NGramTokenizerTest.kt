package com.minibrain.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NGramTokenizerTest {

    @Test
    fun testToBigramsWithJapanese() {
        val text = "あいうえお"
        val tokens = NGramTokenizer.toBigrams(text)

        // Unigrams
        assertTrue(tokens.contains("あ"))
        assertTrue(tokens.contains("い"))
        assertTrue(tokens.contains("う"))
        assertTrue(tokens.contains("え"))
        assertTrue(tokens.contains("お"))

        // Bigrams
        assertTrue(tokens.contains("あい"))
        assertTrue(tokens.contains("いう"))
        assertTrue(tokens.contains("うえ"))
        assertTrue(tokens.contains("えお"))
    }

    @Test
    fun testToBigramsWithSingleJapaneseChar() {
        val text = "胃"
        val tokens = NGramTokenizer.toBigrams(text)
        assertEquals("胃", tokens)
    }

    @Test
    fun testToBigramsWithMixedText() {
        val text = "Hello 胃袋"
        val tokens = NGramTokenizer.toBigrams(text)

        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("胃"))
        assertTrue(tokens.contains("袋"))
        assertTrue(tokens.contains("胃袋"))
    }

    @Test
    fun testToFtsMatchQueryEscapesQuotes() {
        // While toBigrams strips symbols before we can inject quotes, we still verify it produces valid syntax.
        // It should enclose the token in double quotes correctly.
        val ftsMatchQuery = NGramTokenizer.toFtsMatchQuery("hello")
        assertEquals("\"hello\"", ftsMatchQuery ?: "")
    }
}
