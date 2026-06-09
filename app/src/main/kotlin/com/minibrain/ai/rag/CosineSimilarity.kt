package com.minibrain.ai.rag

import kotlin.math.sqrt

object CosineSimilarity {

    fun compute(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must be the same size" }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    fun topK(query: FloatArray, candidates: List<Pair<FloatArray, Any>>, k: Int): List<Pair<Float, Any>> {
        return candidates
            .map { (vec, meta) -> Pair(compute(query, vec), meta) }
            .sortedByDescending { it.first }
            .take(k)
    }
}
