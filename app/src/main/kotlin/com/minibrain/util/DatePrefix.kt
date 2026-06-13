package com.minibrain.util

/**
 * `[日付: YYYY-MM-DD] 本文…` 形式のスニペットプレフィックスを 1 箇所で管理する。
 * SearchPipeline が構築し、LlmReranker がパースし、CoverageChecker / AnswerPrompt が短絡判定に使う。
 * フォーマットを変える場合はこのファイルだけ触れば良いように集約。
 */
object DatePrefix {
    const val OPEN = "[日付:"

    private val REGEX = Regex("""^\[日付:\s*([0-9]{4}-[0-9]{2}-[0-9]{2})\]\s*""")

    fun build(documentDate: String?, body: String?): String = buildString {
        if (!documentDate.isNullOrBlank()) append("[日付: $documentDate] ")
        append(body ?: "")
    }

    /** snippet 先頭の `[日付: YYYY-MM-DD]` プレフィックスを剥がし、(日付, 残り本文) を返す。 */
    fun split(snippet: String): Pair<String?, String> {
        val match = REGEX.find(snippet) ?: return null to snippet
        val date = match.groupValues.getOrNull(1)
        val body = snippet.substring(match.range.last + 1)
        return date to body
    }

    fun hasPrefix(snippet: String): Boolean = snippet.trimStart().startsWith(OPEN)
}
