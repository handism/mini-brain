package com.minibrain.eval

import com.minibrain.ai.agent.SearchTimingEvent
import com.minibrain.ai.rag.Citation
import java.text.Normalizer

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
 *
 * 回答の指標（ADR-048。facts のあるケースで回答を生成したときだけ）:
 * - 事実 Recall : facts のうち回答に含まれていたものの割合
 * - 完全正答率  : facts をすべて含んでいたケースの割合
 * - 答えられず  : 回答の冒頭で「見つかりません」などと答えたケースの数（ADR-054）
 * - 答えの無い質問で控えた割合: unanswerable のケースで、回答のどこかで「見つからない」と答えた割合（ADR-054）
 *
 * unanswerable のケースは、検索の指標（P / R / MRR / 候補 R）と回答の事実の指標から外す。
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
    /** 回答を採点したケースの数。0 なら以下の回答の指標は意味を持たない */
    val answeredCases: Int = 0,
    val factRecall: Double = 0.0,
    val answerAccuracy: Double = 0.0,
    val abstentions: Int = 0,
    /** 答えの無い質問（unanswerable）で回答を生成したケースの数と、そのうち「見つからない」と答えた割合 */
    val unanswerableCases: Int = 0,
    val declineRate: Double = 0.0,
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
    /** 生成した回答。回答を生成しなかったときは null */
    val answer: String? = null,
    /** facts のうち回答に含まれていたもの / 無かったもの */
    val hitFacts: List<String> = emptyList(),
    val missedFacts: List<String> = emptyList(),
    /** 「見つかりません」などと答えたか */
    val abstained: Boolean = false,
    val unanswerable: Boolean = false,
    /** unanswerable のケースで、回答のどこかで「見つからない」と答えたか */
    val declined: Boolean = false,
) {
    /** 回答の採点対象か（回答があり、facts がある） */
    val answerScored: Boolean get() = answer != null && (hitFacts.size + missedFacts.size) > 0
    val factRecall: Double get() = if (answerScored) hitFacts.size.toDouble() / (hitFacts.size + missedFacts.size) else 0.0
}

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
    val answer: String? = null,
)

object EvalMetrics {

    // 回答が根拠を見つけられなかったときの言い回し（AnswerPromptBuilder の指示とモデルの癖から）
    private val ABSTAIN_REGEX = Regex(
        "(情報|記載|記述|記録)(が|は)?(見つかり|見当たり|含まれてい|ありませ|され(てい)?ませ)|見当たりません|書かれていません|載っていません|わかりません|分かりません|特定できません"
    )

    // 照合の前に全角半角・大文字小文字・空白・桁区切りをそろえる
    internal fun normalizeForFact(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().replace(Regex("[\\s,，]"), "")

    /** fact（`|` 区切りの表記ゆれ）のどれかが回答に含まれるか */
    fun answerContainsFact(answer: String, fact: String): Boolean {
        val normalized = normalizeForFact(answer)
        return fact.split('|').map(::normalizeForFact).any { it.isNotEmpty() && it in normalized }
    }

    // 答えた後の補足（「…は記載されていませんが」）は数えない。冒頭の前置きの挨拶を越える程度の長さにする
    private const val ABSTAIN_HEAD_CHARS = 150

    /** 回答の冒頭で、根拠が見つからないと答えたか。補足欄の「記載されていません」は数えない（ADR-054） */
    fun isAbstention(answer: String): Boolean = ABSTAIN_REGEX.containsMatchIn(answer.trimStart().take(ABSTAIN_HEAD_CHARS))

    fun compute(
        cases: List<Pair<EvalCase, List<Citation>>>,
        k: Int,
    ): EvalResult = computeObservations(cases.map { (case, citations) -> EvalObservation(case, citations) }, k)

    fun computeObservations(observations: List<EvalObservation>, k: Int): EvalResult {
        require(k > 0)
        if (observations.isEmpty()) return EvalResult(0, k, 0.0, 0.0, 0.0, emptyList())

        val perCase = observations.map { computeOne(it, k) }
        val answerable = perCase.filterNot { it.unanswerable }
        val avg = { sel: (PerCaseResult) -> Double -> if (answerable.isEmpty()) 0.0 else answerable.sumOf(sel) / answerable.size }
        val scored = perCase.filter { it.answerScored }
        val unanswered = perCase.filter { it.unanswerable && it.answer != null }
        return EvalResult(
            cases = perCase.size,
            k = k,
            precisionAtK = avg { it.precisionAtK },
            recallAtK = avg { it.recallAtK },
            mrr = avg { it.reciprocalRank },
            perCase = perCase,
            candidateRecall = avg { it.candidateRecall },
            totalDurationMs = perCase.sumOf { it.durationMs },
            answeredCases = scored.size,
            factRecall = if (scored.isEmpty()) 0.0 else scored.sumOf { it.factRecall } / scored.size,
            answerAccuracy = if (scored.isEmpty()) 0.0 else scored.count { it.missedFacts.isEmpty() }.toDouble() / scored.size,
            abstentions = answerable.count { it.abstained },
            unanswerableCases = unanswered.size,
            declineRate = if (unanswered.isEmpty()) 0.0 else unanswered.count { it.declined }.toDouble() / unanswered.size,
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
        val answer = obs.answer
        val (hitFacts, missedFacts) = if (answer == null) {
            emptyList<String>() to emptyList()
        } else {
            case.facts.partition { answerContainsFact(answer, it) }
        }
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
            answer = answer,
            hitFacts = hitFacts,
            missedFacts = missedFacts,
            abstained = answer != null && isAbstention(answer),
            unanswerable = case.unanswerable,
            declined = answer != null && ABSTAIN_REGEX.containsMatchIn(answer),
        )
    }
}
