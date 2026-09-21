package com.minibrain.ai.agent

import com.minibrain.ai.rag.SearchRequestCache
import com.minibrain.util.FileNames

/**
 * ReAct の Planner LLM に渡す hint を組み立てる。
 * 期間クエリ（`resolveDateRange`）→ 日付クエリ（YYYYMMDD 8 桁でのパス検索）→ ファイル名一致
 * の順に解析し、` / ` 区切りの 1 行にまとめる（該当なしなら null）。
 */
object PlannerHintBuilder {

    private const val MAX_FILE_MATCHES = 5
    private const val MAX_DATE_MATCHES_PER_DATE = 3

    suspend fun build(
        question: String,
        dateRange: DateRange?,
        cache: SearchRequestCache,
    ): String? {
        val parts = mutableListOf<String>()
        val allDocs = cache.documents()

        // 期間クエリ: resolveDateRange が成功していたら timeline_search を推奨
        if (dateRange != null) {
            parts += "期間クエリ検出: ${dateRange.start} 〜 ${dateRange.end} / timeline_search を推奨"
        }

        if (dateRange == null && DateResolver.isDiaryQuery(question)) {
            parts += buildDateHints(question, allDocs)
        }

        val fileMatches = allDocs
            .filter { doc -> FileNames.stemMatchesAnyQuery(doc.fileName, listOf(question)) }
            .take(MAX_FILE_MATCHES)
        if (fileMatches.isNotEmpty()) {
            parts += "質問にマッチするファイル候補: ${fileMatches.joinToString(", ") { "[d=${it.id}] ${it.fileName}" }}"
        }

        return parts.joinToString(" / ").ifBlank { null }
    }

    private fun buildDateHints(
        question: String,
        allDocs: List<com.minibrain.data.db.entities.DocumentEntity>,
    ): List<String> {
        val dates = DateResolver.resolveToDateStrings(question)
        if (dates.isEmpty()) return emptyList()

        val parts = mutableListOf("検出された日付: ${dates.joinToString(", ")}")

        // パスの正規化（`/` と `-` の除去）は doc 数 × 日付数になるため、ループ外で 1 回だけ行う
        val normalizedDocs = allDocs.map { doc ->
            doc to doc.relativePath.filterNot { it == '/' || it == '-' }
        }

        val found = mutableListOf<String>()
        val notFound = mutableListOf<String>()
        for (date in dates) {
            // 区切り文字を除いた 8 桁数字 (YYYYMMDD) でパスを検索
            val digits = date.replace("-", "")
            val matches = normalizedDocs
                .filter { (_, normalizedPath) -> normalizedPath.contains(digits) }
                .take(MAX_DATE_MATCHES_PER_DATE)
            if (matches.isNotEmpty()) {
                found += matches.map { (doc, _) -> "[d=${doc.id}] ${doc.relativePath}" }
            } else {
                notFound += date
            }
        }

        if (found.isNotEmpty()) {
            parts += "日付に一致するファイル: ${found.joinToString(", ")}"
        }
        if (notFound.isNotEmpty()) {
            // 見つからない場合は YYYYMMDD / YYYY/MM/DD / YYYY-MM-DD の 3 形式を列挙
            val globs = notFound.flatMap { date ->
                listOf(
                    "\"${date.replace("-", "")}*\"",
                    "\"${date.replace("-", "/")}*\"",
                    "\"$date*\"",
                )
            }
            parts += "推奨 glob パターン: ${globs.joinToString(" or ")}"
        }
        return parts
    }
}
