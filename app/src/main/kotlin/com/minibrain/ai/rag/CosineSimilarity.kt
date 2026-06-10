package com.minibrain.ai.rag

object CosineSimilarity {

    fun compute(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must be the same size" }
        // EmbedderService が L2Normalize(true) を使用しているため、ドット積のみでコサイン類似度となる
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot
    }

    fun topK(query: FloatArray, candidates: List<Pair<FloatArray, Any>>, k: Int): List<Pair<Float, Any>> {
        return candidates
            .map { (vec, meta) -> Pair(compute(query, vec), meta) }
            .sortedByDescending { it.first }
            .take(k)
    }
}
