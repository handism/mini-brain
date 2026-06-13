package com.minibrain.data.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownChunkerTest {

    @Test
    fun testChunkingShortText() {
        val markdown = "Hello World"
        val chunks = MarkdownChunker.chunk(markdown, "test.md")
        assertEquals(1, chunks.size)
        assertEquals("Hello World", chunks[0].text)
    }

    @Test
    fun testChunkingLongParagraph() {
        // 1000 characters paragraph
        val longPara = "A".repeat(1000)
        val chunks = MarkdownChunker.chunk(longPara, "test.md")

        // MAX_CHUNK_CHARS = 800, OVERLAP = 120
        // First chunk: 0-800
        // Second chunk: starts from 800-120 = 680. remaining is 1000-680 = 320.
        assertEquals(2, chunks.size)
        assertEquals(800, chunks[0].text.length)
        assertEquals(320, chunks[1].text.length)

        // Verify overlap (single character paragraph なので末尾/先頭が同じ A の連続）
        assertTrue(chunks[0].text.endsWith("A".repeat(120)))
        assertTrue(chunks[1].text.startsWith("A".repeat(120)))
    }

    @Test
    fun testChunkingMultipleSections() {
        val markdown = """
            # Section 1
            Body 1

            # Section 2
            Body 2
        """.trimIndent()
        val chunks = MarkdownChunker.chunk(markdown, "test.md")
        assertEquals(2, chunks.size)
        assertEquals("test.md > Section 1", chunks[0].headingPath)
        assertEquals("Body 1", chunks[0].text)
        assertEquals("test.md > Section 2", chunks[1].headingPath)
        // セクション境界 tail carry により、前セクション末尾（Body 1）が Section 2 の頭に付与される
        assertTrue(
            "Section 2 のチャンクに直前セクションの tail (Body 1) が含まれる: actual=${chunks[1].text}",
            chunks[1].text.contains("Body 1"),
        )
        assertTrue(chunks[1].text.contains("Body 2"))
    }
}
