package com.minibrain.ai.rag

/**
 * Reciprocal Rank Fusion の汎用コア。
 * score = Σ weight × 1/(k + rank + 1)。複数ソースに出る項目ほど加点される。
 * 同キーで衝突した場合は最初に出現したアイテムを保持する（first-wins）。
 * 並び替え・カットは呼び出し側で行う。
 */
object RrfFuser {
    data class Fused<T>(val key: Any, val score: Float, val item: T)

    fun <T> fuse(
        rankLists: List<List<T>>,
        keyOf: (T) -> Any,
        k: Int = 60,
        weights: List<Float>? = null,
    ): List<Fused<T>> {
        require(weights == null || weights.size == rankLists.size) {
            "weights.size must equal rankLists.size"
        }
        val scores = mutableMapOf<Any, Float>()
        val first = mutableMapOf<Any, T>()
        rankLists.forEachIndexed { listIdx, list ->
            val w = weights?.get(listIdx) ?: 1f
            list.forEachIndexed { rank, item ->
                val key = keyOf(item)
                scores[key] = (scores[key] ?: 0f) + w * (1f / (k + rank + 1))
                first.getOrPut(key) { item }
            }
        }
        return scores.map { (key, score) -> Fused(key, score, first.getValue(key)) }
    }
}
