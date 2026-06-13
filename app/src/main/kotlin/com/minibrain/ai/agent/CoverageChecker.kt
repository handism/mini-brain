package com.minibrain.ai.agent

import android.util.Log
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation

data class CoverageResult(
    val canAnswer: Boolean,
    val missingInformation: List<String>,
)

class CoverageChecker(private val llmService: LlmService) {

    companion object {
        private const val TAG = "CoverageChecker"
        private const val SNIPPET_MAX_CHARS = 300
        private const val MAX_CANDIDATES = 5
        private const val DATE_PREFIX = "[日付:"
        private val DATE_QUERY_REGEX = Regex("""いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで""")
    }

    suspend fun check(query: String, candidates: List<Citation>): CoverageResult {
        // 日付クエリで日付プレフィックス付き候補があれば、LLM を呼ばずに即答可能と判定
        if (DATE_QUERY_REGEX.containsMatchIn(query) &&
            candidates.take(MAX_CANDIDATES).any { it.snippet.trimStart().startsWith(DATE_PREFIX) }
        ) {
            Log.d(TAG, "short-circuit: date query with dated candidate → canAnswer=true")
            return CoverageResult(canAnswer = true, missingInformation = emptyList())
        }

        if (!llmService.isReady()) return CoverageResult(canAnswer = true, missingInformation = emptyList())

        val prompt = buildPrompt(query, candidates.take(MAX_CANDIDATES))
        val sb = StringBuilder()
        runCatching {
            llmService.generateStream(prompt).collect { token -> sb.append(token) }
        }.onFailure {
            Log.w(TAG, "coverage check LLM failed", it)
            return CoverageResult(canAnswer = true, missingInformation = emptyList())
        }

        return parse(sb.toString().trim().lowercase())
    }

    private fun buildPrompt(query: String, candidates: List<Citation>): String {
        val sb = StringBuilder()
        sb.appendLine("以下の検索結果は質問に直接回答できる情報（具体的な日付・出来事など）を含んでいますか？")
        sb.appendLine("「yes」または「no, 不足情報キーワード」の形式のみで答えてください。")
        sb.appendLine()
        sb.appendLine("質問: \"$query\"")
        sb.appendLine("検索結果:")
        candidates.forEachIndexed { i, c ->
            val snippet = c.snippet.take(SNIPPET_MAX_CHARS).replace('\n', ' ')
            sb.appendLine("[$i] ${c.headingPath}: $snippet")
        }
        sb.appendLine()
        sb.appendLine("出力例: yes  /  no, visit_date  /  no, event_date, location")
        sb.append("出力:")
        return sb.toString()
    }

    private fun parse(raw: String): CoverageResult {
        // 全体から "yes" または "no, ..." を探す。
        // 行単位でスキャンして最初に見つかった妥当な方を採用。
        for (rawLine in raw.lines()) {
            val line = rawLine.trim().lowercase()
            if (line.isEmpty()) continue

            if (line.startsWith("yes")) {
                return CoverageResult(canAnswer = true, missingInformation = emptyList())
            }
            if (line.startsWith("no")) {
                // "no, key1, key2" 形式を想定
                val parts = line.substringAfter("no").trim().trimStart(',', ' ')
                val missing = if (parts.isBlank()) emptyList()
                else parts.split(",").map { it.trim() }.filter { it.isNotBlank() }
                Log.d(TAG, "coverage=false missing=$missing")
                return CoverageResult(canAnswer = false, missingInformation = missing)
            }
        }

        // 判定不能時は「回答可能」扱いにして余計なReActを起動しない
        Log.d(TAG, "coverage parse unclear: $raw — defaulting canAnswer=true")
        return CoverageResult(canAnswer = true, missingInformation = emptyList())
    }
}
