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
        assertEquals(2, chunks.size)
        assertEquals(800, chunks[0].text.length)

        // Verify overlap
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
        assertEquals("Body 1\n\nBody 2", chunks[1].text)
    }

    @Test
    fun testChunkingEmptyOrBlankText() {
        assertTrue(MarkdownChunker.chunk("", "test.md").isEmpty())
        assertTrue(MarkdownChunker.chunk("   \n  \t ", "test.md").isEmpty())
    }

    @Test
    fun testChunkingTextBeforeFirstHeading() {
        val markdown = """
            This is an intro preamble.

            # Section 1
            Body 1
        """.trimIndent()
        val chunks = MarkdownChunker.chunk(markdown, "test.md")
        assertEquals(2, chunks.size)
        assertEquals("test.md", chunks[0].headingPath)
        assertEquals("This is an intro preamble.", chunks[0].text)

        assertEquals("test.md > Section 1", chunks[1].headingPath)
        // Checks SECTION_TAIL_CARRY behavior where intro preamble tail is prepended to Section 1
        assertEquals("This is an intro preamble.\n\nBody 1", chunks[1].text)
    }

    @Test
    fun testChunkingNestedSections() {
        val markdown = """
            # H1
            Body H1

            ## H2
            Body H2

            # H1-2
            Body H1-2
        """.trimIndent()

        val chunks = MarkdownChunker.chunk(markdown, "test.md")
        assertEquals(3, chunks.size)

        assertEquals("test.md > H1", chunks[0].headingPath)
        assertEquals("Body H1", chunks[0].text)

        assertEquals("test.md > H1 > H2", chunks[1].headingPath)
        assertEquals("Body H1\n\nBody H2", chunks[1].text)

        assertEquals("test.md > H1-2", chunks[2].headingPath)
        assertEquals("Body H2\n\nBody H1-2", chunks[2].text)
    }

    @Test
    fun testChunkingEmptySection() {
        val markdown = """
            # Section 1

            # Section 2
            Body 2
        """.trimIndent()
        val chunks = MarkdownChunker.chunk(markdown, "test.md")
        // Section 1 is blank, so it shouldn't be included as a chunk directly, but
        // because of tail carry, we should just get Section 2 with its body
        assertEquals(1, chunks.size)
        assertEquals("test.md > Section 2", chunks[0].headingPath)
        assertEquals("Body 2", chunks[0].text)
    }

    @Test
    fun testChunkingCodeBlocksAndParagraphs() {
        val longBody = "A".repeat(400)
        val markdown = """
            # Code
            $longBody

            ```
            Line 1

            Line 2
            ```

            $longBody
        """.trimIndent()

        val chunks = MarkdownChunker.chunk(markdown, "test.md")

        // This is a single section but because it exceeds MAX_CHUNK_CHARS (800) and contains multiple paragraphs,
        // it should be split into multiple chunks, but the code block should not be broken in the middle of blank lines.
        assertTrue(chunks.size >= 2)
        assertEquals("test.md > Code", chunks[0].headingPath)
        assertTrue(chunks.any { it.text.contains("```\nLine 1\n\nLine 2\n```") })
    }
}
