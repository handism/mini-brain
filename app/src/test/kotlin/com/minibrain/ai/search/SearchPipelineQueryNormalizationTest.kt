package com.minibrain.ai.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPipelineQueryNormalizationTest {

    @Test
    fun `buildUniqueNormalizedQueries handles null, blank, spaces, and deduplication correctly`() {
        val originalQuery = "  main   query  \n"
        val expanded = listOf(
            " expanded   query ",
            "  ",
            "main query"
        )
        val hypothetical = "  hypo \n answer  "

        val results = SearchPipeline.buildUniqueNormalizedQueries(
            originalQuery,
            expanded,
            hypothetical
        )

        val expected = setOf("main query", "expanded query", "hypo answer")

        assertEquals(expected, results)

        // Ensure that order is preserved (LinkedHashSet behavior)
        assertEquals(listOf("main query", "expanded query", "hypo answer"), results.toList())
    }

    @Test
    fun `buildUniqueNormalizedQueries returns empty when all inputs are invalid`() {
        val results = SearchPipeline.buildUniqueNormalizedQueries(
            null,
            listOf("", "  ", "\n"),
            null
        )

        assertEquals(emptySet<String>(), results)
    }
}
