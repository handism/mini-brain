package com.minibrain.eval

import com.minibrain.ai.rag.Citation

// 検索評価指標の純 Kotlin 実装。JVM ユニットテストで数値検証する。
//
// - Precision@K: 上位 K 件のうち正解集合に含まれるものの割合
// - Recall@K   : 正解集合のうち上位 K 件で拾えたものの割合
// - MRR        : 正解が初めて出現した順位の逆数（出現しなければ 0）
//
// ケース全体の集計は単純な算術平均（マイクロではなくマクロ平均）。
data class EvalResult(
    val cases: Int,
    val k: Int,
    val precisionAtK: Double,
    val recallAtK: Double,
    val mrr: Double,
    val perCase: List<PerCaseResult>,
)

data class PerCaseResult(
    val id: String,
    val query: String,
    val precisionAtK: Double,
    val recallAtK: Double,
    val reciprocalRank: Double,
    val hitPaths: List<String>,
    val missedPaths: List<String>,
)

object EvalMetrics {

    fun compute(
        cases: List<Pair<EvalCase, List<Citation>>>,
        k: Int,
    ): EvalResult {
        require(k > 0)
        if (cases.isEmpty()) return EvalResult(0, k, 0.0, 0.0, 0.0, emptyList())

        val perCase = cases.map { (case, citations) -> computeOne(case, citations, k) }
        val avg = { sel: (PerCaseResult) -> Double -> perCase.sumOf(sel) / perCase.size }
        return EvalResult(
            cases = perCase.size,
            k = k,
            precisionAtK = avg { it.precisionAtK },
            recallAtK = avg { it.recallAtK },
            mrr = avg { it.reciprocalRank },
            perCase = perCase,
        )
    }

    private fun computeOne(case: EvalCase, citations: List<Citation>, k: Int): PerCaseResult {
        val expected = case.expectedRelativePaths.map { it.lowercase() }.toSet()
        val topK = citations.take(k)
        val retrievedPaths = topK.mapNotNull { it.relativePath?.lowercase() }

        val hits = retrievedPaths.filter { it in expected }.toSet()
        val precision = if (topK.isEmpty()) 0.0 else hits.size.toDouble() / topK.size
        val recall = if (expected.isEmpty()) 0.0 else hits.size.toDouble() / expected.size

        val firstHitRank = retrievedPaths.indexOfFirst { it in expected }
        val rr = if (firstHitRank < 0) 0.0 else 1.0 / (firstHitRank + 1)

        return PerCaseResult(
            id = case.id,
            query = case.query,
            precisionAtK = precision,
            recallAtK = recall,
            reciprocalRank = rr,
            hitPaths = hits.toList(),
            missedPaths = (expected - hits).toList(),
        )
    }
}
