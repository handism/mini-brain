package com.minibrain.util

/**
 * 会話履歴のレンダリングを 1 箇所に集約する。AgentPipeline / RagPipeline で同じフォーマットを使う。
 */
object PromptUtils {
    private const val MAX_HISTORY_TURNS = 6

    /**
     * Renders the conversation history block, keeping only the last [MAX_HISTORY_TURNS].
     * Maps "user" role to "ユーザー" and other roles to "アシスタント".
     */
    fun renderHistoryBlock(history: List<Pair<String, String>>): String {
        if (history.isEmpty()) return ""
        val rendered = history.takeLast(MAX_HISTORY_TURNS)
            .joinToString("\n") { (role, content) ->
                "${if (role == "user") "ユーザー" else "アシスタント"}: $content"
            }
        return if (rendered.isBlank()) "" else "$rendered\n"
    }
}
