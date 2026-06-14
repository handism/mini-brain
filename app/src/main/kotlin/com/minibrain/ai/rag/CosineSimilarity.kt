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

    fun topK(query: FloatArray, candidates: List<Pair<FloatArray, Any>>, k: Int): List<Pair<Float, Any>> {
        if (k <= 0 || candidates.isEmpty()) return emptyList()

        // 最小ヒープを使い、スコアが最小のものを先頭にする。
        // サイズが k を超えたら、最小スコアのものを queue から取り除くことで、上位 k 件を維持する。
        val queue = PriorityQueue<Pair<Float, Any>>(k + 1, compareBy { it.first })

        for (cand in candidates) {
            val score = compute(query, cand.first)
            queue.add(Pair(score, cand.second))
            if (queue.size > k) {
                queue.poll()
            }
        }

        // PriorityQueue から取り出して、降順にソートする
        val result = ArrayList<Pair<Float, Any>>(queue.size)
        while (queue.isNotEmpty()) {
            queue.poll()?.let { result.add(it) }
        }
        result.reverse() // 最小ヒープから取り出したので昇順になっており、反転して降順にする
        return result
    }
}
