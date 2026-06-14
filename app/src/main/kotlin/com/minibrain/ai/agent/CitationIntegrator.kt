package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import com.minibrain.ai.rag.dedupeKey
import com.minibrain.util.TokenEstimator

object CitationIntegrator {



    private val SOURCE_PRIORITY = listOf(
        SourceType.READ_FILE,
        SourceType.GREP,
        SourceType.METADATA,
        SourceType.BM25,
        SourceType.VECTOR,
        SourceType.RRF,
        SourceType.GLOB,
        SourceType.FOLDER,
        SourceType.UNKNOWN,
    )

    fun integrate(results: List<com.minibrain.ai.agent.ToolResult>): List<Citation> {
        val seen = mutableMapOf<String, Citation>()

        results.forEach { result ->
            result.citations.forEach { citation ->
                val key = citation.dedupeKey
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
        var remainingTokens = TokenEstimator.MAX_CONTEXT_TOKENS
        for (c in sorted) {
            val cost = TokenEstimator.estimate(c.headingPath, c.snippet) + 5
            if (remainingTokens <= 0) break
            budgeted += c
            remainingTokens -= cost
        }
        return budgeted
    }

    private fun priorityOf(source: SourceType): Int = SOURCE_PRIORITY.indexOf(source).let {
        if (it < 0) SOURCE_PRIORITY.size else it
    }
}
