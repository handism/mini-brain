package com.minibrain.eval

import com.minibrain.ai.agent.SearchTimingEvent
import java.util.Locale

/**
 * 評価結果を Markdown にする。施策の前後でコピーして並べ、差分を見る用途。
 * 行の順序と書式を変えると過去のレポートと比べにくくなるので、足すときは末尾に足す。
 */
object EvalReport {

    fun toMarkdown(
        result: EvalResult,
        title: String,
        unknownPaths: List<String> = emptyList(),
    ): String = buildString {
        val k = result.k
        appendLine("# $title")
        appendLine()
        appendLine("- ケース数: ${result.cases} / K=$k")
        appendLine("- Recall@$k: ${fmt(result.recallAtK)}")
        appendLine("- MRR: ${fmt(result.mrr)}")
        appendLine("- Precision@$k: ${fmt(result.precisionAtK)}")
        appendLine("- 候補 Recall（絞り込み前）: ${fmt(result.candidateRecall)}")
        appendLine("- 所要時間: ${formatDuration(result.totalDurationMs)}（平均 ${formatSeconds(averageMs(result))} 秒/件）")

        if (unknownPaths.isNotEmpty()) {
            appendLine()
            appendLine("## インデックスに無い正解パス")
            unknownPaths.forEach { appendLine("- $it") }
        }

        val misses = result.perCase.filter { it.missedPaths.isNotEmpty() || it.error != null }
        if (misses.isNotEmpty()) {
            appendLine()
            appendLine("## 取りこぼし")
            misses.forEach { c ->
                appendLine("- [${c.id}] ${c.query}（R=${fmt(c.recallAtK)} RR=${fmt(c.reciprocalRank)}）")
                c.error?.let { appendLine("  - エラー: $it") }
                c.missedPaths.forEach { path ->
                    val reason = if (path in c.droppedByRerank) "絞り込みで落ちた" else "候補に無い"
                    appendLine("  - $path: $reason")
                }
                if (c.retrievedPaths.isNotEmpty()) {
                    appendLine("  - 上位: ${c.retrievedPaths.take(5).joinToString(", ")}")
                }
            }
        }

        val expectedScores = result.perCase.mapNotNull { it.expectedVectorScore }
        val otherScores = result.perCase.mapNotNull { it.otherVectorScore }
        if (expectedScores.isNotEmpty() || otherScores.isNotEmpty()) {
            appendLine()
            appendLine("## ベクトル類似度")
            appendLine("- 正解チャンクの最高類似度: ${scoreSummary(expectedScores)}")
            appendLine("- 正解以外の最高類似度: ${scoreSummary(otherScores)}")
        }

        appendLine()
        appendLine("## ケース別")
        appendLine("| id | Recall | RR | 候補R | 秒 | 正解類似度 | 他類似度 | 内訳（展開/HyDE/検索/絞込） |")
        appendLine("|---|---|---|---|---|---|---|---|")
        result.perCase.forEach { c ->
            appendLine(
                "| ${c.id} | ${fmt(c.recallAtK)} | ${fmt(c.reciprocalRank)} | " +
                    "${fmt(c.candidateRecall)} | ${formatSeconds(c.durationMs)} | " +
                    "${c.expectedVectorScore?.let(::fmt) ?: "-"} | ${c.otherVectorScore?.let(::fmt) ?: "-"} | " +
                    "${c.timing?.let(::formatTiming) ?: "-"} |"
            )
        }
    }.trimEnd()

    // 最小 / 中央値 / 最大（件数）
    private fun scoreSummary(scores: List<Double>): String {
        if (scores.isEmpty()) return "-"
        val sorted = scores.sorted()
        val median = sorted[sorted.size / 2]
        return "最小 ${fmt(sorted.first())} / 中央値 ${fmt(median)} / 最大 ${fmt(sorted.last())}（${sorted.size} 件）"
    }

    private fun formatTiming(t: SearchTimingEvent): String =
        listOf(t.expansionMs, t.hydeMs, t.retrievalMs, t.rerankMs).joinToString("/", transform = ::formatSeconds)

    fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun averageMs(result: EvalResult): Long =
        if (result.cases == 0) 0L else result.totalDurationMs / result.cases

    private fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return if (totalSec >= 60) "${totalSec / 60}分${totalSec % 60}秒" else "${totalSec}秒"
    }
}
