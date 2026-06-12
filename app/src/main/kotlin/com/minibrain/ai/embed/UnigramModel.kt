package com.minibrain.ai.embed

/**
 * SentencePiece Unigram のサブワード分割（Viterbi）。
 * HuggingFace tokenizers の Unigram model と同等: 未知文字は min_score - 10 のペナルティで
 * <unk> にフォールバックし、隣接する <unk> は 1 つに融合する (fuse_unk)。
 */
class UnigramModel(
    private val pieceIds: Map<String, Int>,
    private val scores: DoubleArray,
    private val unkId: Int,
) {
    private val maxPieceLength: Int = pieceIds.keys.maxOf { it.length }
    private val unkScore: Double = scores.min() - UNK_PENALTY

    fun tokenize(segment: String): List<Int> {
        val n = segment.length
        if (n == 0) return emptyList()
        val bestScore = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val backPos = IntArray(n + 1)
        val backId = IntArray(n + 1)
        bestScore[0] = 0.0

        var i = 0
        while (i < n) {
            val base = bestScore[i]
            if (base != Double.NEGATIVE_INFINITY) {
                val cpLen = Character.charCount(segment.codePointAt(i))
                var singleMatched = false
                val maxEnd = minOf(n, i + maxPieceLength)
                var j = i + 1
                while (j <= maxEnd) {
                    val id = pieceIds[segment.substring(i, j)]
                    if (id != null) {
                        if (j - i == cpLen) singleMatched = true
                        val cand = base + scores[id]
                        if (cand > bestScore[j]) {
                            bestScore[j] = cand
                            backPos[j] = i
                            backId[j] = id
                        }
                    }
                    j++
                }
                if (!singleMatched) {
                    val end = i + cpLen
                    val cand = base + unkScore
                    if (cand > bestScore[end]) {
                        bestScore[end] = cand
                        backPos[end] = i
                        backId[end] = unkId
                    }
                }
            }
            i++
        }

        val reversed = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            reversed.add(backId[pos])
            pos = backPos[pos]
        }

        val ids = ArrayList<Int>(reversed.size)
        for (k in reversed.indices.reversed()) {
            val id = reversed[k]
            if (id == unkId && ids.lastOrNull() == unkId) continue
            ids.add(id)
        }
        return ids
    }

    companion object {
        private const val UNK_PENALTY = 10.0
    }
}
