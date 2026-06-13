package com.minibrain.util

/**
 * 会話履歴のレンダリングを 1 箇所に集約する。AgentPipeline / RagPipeline で同じフォーマットを使う。
 */
object PromptUtils {
    private const val MAX_HISTORY_TURNS = 6

    fun renderHistoryBlock(history: List<Pair<String, String>>): String {
        if (history.isEmpty()) return ""
        val rendered = history.takeLast(MAX_HISTORY_TURNS)
            .joinToString("\n") { (role, content) ->
                "${if (role == "user") "ユーザー" else "アシスタント"}: $content"
            }
        return if (rendered.isBlank()) "" else "$rendered\n"
    }
}
