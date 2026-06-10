package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType

object CitationIntegrator {

    private const val MAX_CITATION_CHARS = 4000

    private val SOURCE_PRIORITY = listOf(
        SourceType.READ_FILE,
        SourceType.GREP,
        SourceType.VECTOR,
        SourceType.RRF,
        SourceType.GLOB,
        SourceType.UNKNOWN,
    )

    fun integrate(results: List<com.minibrain.ai.agent.ToolResult>): List<Citation> {
        val seen = mutableMapOf<String, Citation>()

        results.forEach { result ->
            result.citations.forEach { citation ->
                val key = "${citation.docId}::${citation.headingPath}"
                val existing = seen[key]
                if (existing == null || priorityOf(citation.source) < priorityOf(existing.source)) {
                    seen[key] = citation
                } else if (citation.source == existing.source && citation.score > existing.score) {
                    seen[key] = citation
                }
            }
        }

        val sorted = seen.values.sortedWith(
            compareBy<Citation> { priorityOf(it.source) }.thenByDescending { it.score }
        )

        val budgeted = mutableListOf<Citation>()
        var remaining = MAX_CITATION_CHARS
        for (c in sorted) {
            val cost = c.headingPath.length + c.snippet.length + 6
            if (remaining <= 0) break
            budgeted += c
            remaining -= cost
        }
        return budgeted
    }

    private fun priorityOf(source: SourceType): Int = SOURCE_PRIORITY.indexOf(source).let {
        if (it < 0) SOURCE_PRIORITY.size else it
    }
}
