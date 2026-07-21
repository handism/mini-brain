package com.minibrain.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Test

class CitationKeyTest {

    @Test
    fun `dedupeKey format should be docId double colon headingPath`() {
        val citation = Citation(
            docId = 42L,
            headingPath = "Introduction/History",
            snippet = "Sample snippet"
        )

        assertEquals("42::Introduction/History", citation.dedupeKey)
    }

    @Test
    fun `dedupeKey should handle null docId`() {
        val citation = Citation(
            docId = null,
            headingPath = "Conclusion",
            snippet = "Sample snippet"
        )

        assertEquals("null::Conclusion", citation.dedupeKey)
    }

    @Test
    fun `dedupeKey should handle empty headingPath`() {
        val citation = Citation(
            docId = 1L,
            headingPath = "",
            snippet = "Sample snippet"
        )

        assertEquals("1::", citation.dedupeKey)
    }
}
