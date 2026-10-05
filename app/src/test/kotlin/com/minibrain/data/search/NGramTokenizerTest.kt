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
        val text = "歯"
        val tokens = NGramTokenizer.toBigrams(text)
        assertEquals("歯", tokens)
    }

    @Test
    fun testToBigramsWithMixedText() {
        val text = "Hello 歯車"
        val tokens = NGramTokenizer.toBigrams(text)

        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("歯"))
        assertTrue(tokens.contains("車"))
        assertTrue(tokens.contains("歯車"))
    }
    @Test
    fun testToFtsMatchQueryEscapesQuotes() {
        // While toBigrams strips symbols before we can inject quotes, we still verify it produces valid syntax.
        // It should enclose the token in double quotes correctly.
        val ftsMatchQuery = NGramTokenizer.toFtsMatchQuery("hello")
        assertEquals("\"hello\"", ftsMatchQuery ?: "")

        val result = NGramTokenizer.toFtsMatchQuery("hello\"world")
        assertEquals("\"hello\" OR \"world\"", result!!)
    }

    @Test
    fun `検索トークンは 2 文字以上があれば日本語の 1 文字を外す`() {
        val tokens = NGramTokenizer.toQueryTokens("カレーの記録")
        assertTrue(tokens.contains("カレ"))
        assertTrue(tokens.none { it.length == 1 })
    }

    @Test
    fun `1 文字だけのクエリは 1 文字トークンを残す`() {
        assertEquals(listOf("歯"), NGramTokenizer.toQueryTokens("歯"))
    }

    @Test
    fun `英単語は 1 文字でも残す`() {
        assertEquals(listOf("c", "言語"), NGramTokenizer.toQueryTokens("C 言語"))
    }
}

