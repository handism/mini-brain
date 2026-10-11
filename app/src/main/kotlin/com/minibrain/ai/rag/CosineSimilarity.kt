package com.minibrain.ai.rag

import java.util.PriorityQueue

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

    /**
     * candidates の要素型を型引数で受けるため、呼び出し側は `Any` へのキャスト
     * （およびその `@Suppress("UNCHECKED_CAST")`）を書かずに済む。
     */
    inline fun <T> topK(
        query: FloatArray,
        candidates: Iterable<T>,
        k: Int,
        crossinline vectorSelector: (T) -> FloatArray
    ): List<Pair<Float, T>> {
        if (k <= 0) return emptyList()

        val queue = PriorityQueue<Pair<Float, T>>(k + 1, compareBy { it.first })

        for (cand in candidates) {
            val vector = vectorSelector(cand)
            val score = compute(query, vector)
            queue.add(Pair(score, cand))
            if (queue.size > k) {
                queue.poll()
            }
        }

        val result = ArrayList<Pair<Float, T>>(queue.size)
        while (queue.isNotEmpty()) {
            queue.poll()?.let { result.add(it) }
        }
        result.reverse()
        return result
    }
}
