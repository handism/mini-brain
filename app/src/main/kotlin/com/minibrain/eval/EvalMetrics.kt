package com.minibrain.eval

import com.minibrain.ai.agent.SearchTimingEvent
import com.minibrain.ai.rag.Citation

/**
 * 検索評価指標の純 Kotlin 実装。JVM ユニットテストで数値検証する。
 *
 * - Precision@K: 上位 K 件のうち正解集合に含まれるものの割合
 * - Recall@K   : 正解集合のうち上位 K 件で拾えたものの割合
 * - MRR        : 正解が初めて出現した順位の逆数（出現しなければ 0）
 * - 候補 Recall : 正解集合のうち Reranker 前の候補（RRF 融合後）に入っていたものの割合。
 *                Recall@K との差が「絞り込みで落ちた」分になる
 *
 * ケース全体の集計は単純な算術平均（マイクロではなくマクロ平均）。
 */
data class EvalResult(
    val cases: Int,
    val k: Int,
    val precisionAtK: Double,
    val recallAtK: Double,
    val mrr: Double,
    val perCase: List<PerCaseResult>,
    val candidateRecall: Double = recallAtK,
    val totalDurationMs: Long = 0L,
)

data class PerCaseResult(
    val id: String,
    val query: String,
    val precisionAtK: Double,
    val recallAtK: Double,
    val reciprocalRank: Double,
    val hitPaths: List<String>,
    val missedPaths: List<String>,
    val candidateRecall: Double = recallAtK,
    /** missedPaths のうち、候補には入っていたが上位 K 件に残らなかったもの */
    val droppedByRerank: List<String> = emptyList(),
    /** droppedByRerank の各パスが候補（ファイル単位）の何番目にあったか（0 始まり）。Reranker に渡ったかを見る（ADR-047） */
    val candidateRanks: Map<String, Int> = emptyMap(),
    /** 上位 K 件の relativePath（順位順、重複なし）。取りこぼしの原因を眺めるため */
    val retrievedPaths: List<String> = emptyList(),
    val durationMs: Long = 0L,
    val error: String? = null,
    /** ベクトル検索で正解ファイルのチャンクが取った最高類似度。ベクトルで拾えていなければ null */
    val expectedVectorScore: Double? = null,
    /** ベクトル検索で正解以外のチャンクが取った最高類似度。VECTOR_MIN_SCORE の見直しに使う（ADR-040） */
    val otherVectorScore: Double? = null,
    /** 段階ごとの所要時間。遅いケースの原因を切り分けるため（ADR-044） */
    val timing: SearchTimingEvent? = null,
)

/** 1 ケース分の検索結果。candidates が null なら citations を候補とみなす。 */
data class EvalObservation(
    val case: EvalCase,
    val citations: List<Citation>,
    val candidates: List<Citation>? = null,
    val durationMs: Long = 0L,
    val error: String? = null,
    /** しきい値を通ったベクトル検索の結果（score は類似度） */
    val vectorHits: List<Citation> = emptyList(),
    val timing: SearchTimingEvent? = null,
)

object EvalMetrics {

    fun compute(
        cases: List<Pair<EvalCase, List<Citation>>>,
        k: Int,
    ): EvalResult = computeObservations(cases.map { (case, citations) -> EvalObservation(case, citations) }, k)

    fun computeObservations(observations: List<EvalObservation>, k: Int): EvalResult {
        require(k > 0)
        if (observations.isEmpty()) return EvalResult(0, k, 0.0, 0.0, 0.0, emptyList())

        val perCase = observations.map { computeOne(it, k) }
        val avg = { sel: (PerCaseResult) -> Double -> perCase.sumOf(sel) / perCase.size }
        return EvalResult(
            cases = perCase.size,
            k = k,
            precisionAtK = avg { it.precisionAtK },
            recallAtK = avg { it.recallAtK },
            mrr = avg { it.reciprocalRank },
            perCase = perCase,
            candidateRecall = avg { it.candidateRecall },
            totalDurationMs = perCase.sumOf { it.durationMs },
        )
    }

    /** インデックスに無い正解パス（打ち間違い・移動済み）を拾う。大文字小文字は無視する。 */
    fun findUnknownPaths(cases: List<EvalCase>, indexedPaths: Collection<String>): List<String> {
        val known = indexedPaths.map { it.lowercase() }.toHashSet()
        return cases.flatMap { it.expectedRelativePaths }
            .distinct()
            .filterNot { it.lowercase() in known }
    }

    private fun computeOne(obs: EvalObservation, k: Int): PerCaseResult {
        val case = obs.case
        val expected = case.expectedRelativePaths.map { it.lowercase() }.toSet()
        val topK = obs.citations.take(k)
        val retrievedPaths = topK.mapNotNull { it.relativePath?.lowercase() }

        val hits = retrievedPaths.filter { it in expected }.toSet()
        val precision = if (topK.isEmpty()) {
            if (expected.isEmpty()) 1.0 else 0.0
        } else {
            hits.size.toDouble() / topK.size
        }
        val recall = if (expected.isEmpty()) 1.0 else hits.size.toDouble() / expected.size

        val firstHitRank = retrievedPaths.indexOfFirst { it in expected }
        val rr = if (firstHitRank < 0) 0.0 else 1.0 / (firstHitRank + 1)

        // Reranker が上位 K 件に無い候補を足すことはない（日付 pin も候補由来）が、
        // 念のため上位 K 件の hit も候補側に含めて「候補 Recall >= Recall@K」を保つ
        val candidateOrder = (obs.candidates ?: obs.citations)
            .mapNotNull { it.relativePath?.lowercase() }
            .distinct()
        val candidatePaths = candidateOrder.toSet() + hits
        val candidateHits = expected.filter { it in candidatePaths }
        val candidateRecall = if (expected.isEmpty()) 1.0 else candidateHits.size.toDouble() / expected.size

        val (expectedVec, otherVec) = obs.vectorHits.partition { it.relativePath?.lowercase() in expected }

        val missed = expected - hits
        return PerCaseResult(
            id = case.id,
            query = case.query,
            precisionAtK = precision,
            recallAtK = recall,
            reciprocalRank = rr,
            hitPaths = hits.toList(),
            missedPaths = missed.toList(),
            candidateRecall = candidateRecall,
            droppedByRerank = missed.filter { it in candidatePaths },
            candidateRanks = missed.associateWith { candidateOrder.indexOf(it) }.filterValues { it >= 0 },
            retrievedPaths = retrievedPaths.distinct(),
            durationMs = obs.durationMs,
            error = obs.error,
            expectedVectorScore = expectedVec.maxOfOrNull { it.score.toDouble() },
            otherVectorScore = otherVec.maxOfOrNull { it.score.toDouble() },
            timing = obs.timing,
        )
    }
}
