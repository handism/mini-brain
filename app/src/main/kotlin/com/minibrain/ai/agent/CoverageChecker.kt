package com.minibrain.ai.agent

import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.util.DatePrefix
import timber.log.Timber

data class CoverageResult(
    val canAnswer: Boolean,
    val missingInformation: List<String>,
)

class CoverageChecker(private val llmService: LlmService) {

    companion object {
        private const val TAG = "CoverageChecker"
        private const val SNIPPET_MAX_CHARS = 300
        private const val MAX_CANDIDATES = 5

        // 日付クエリで上位候補に日付プレフィックス付き snippet があるか（LLM 短絡判定）
        internal fun isDateShortCircuit(query: String, candidates: List<Citation>): Boolean =
            DateResolver.isDateQuery(query) &&
                candidates.take(MAX_CANDIDATES).any { DatePrefix.hasPrefix(it.snippet) }

        // 日付クエリ + 固有名詞ヒット (topicMatch) があれば、documentDate が空でも即答可能と判定する（ADR-026）。
        // ファイル名が質問にそのまま入っている時点で対象ファイルは確定しており、本文中の日付は回答 LLM が拾えばよい。
        // ここで no を返すと citations がリセットされて ReAct に落ち、当該ファイルが落ちる事故を防ぐ。
        internal fun isTopicMatchShortCircuit(query: String, candidates: List<Citation>): Boolean =
            DateResolver.isDateQuery(query) &&
                candidates.take(MAX_CANDIDATES).any { it.topicMatch }

        internal fun parse(raw: String): CoverageResult {
            // LLM が「回答は: yes, ...」のように前置きを付けることがあるため、
            // 全行をスキャンして最初に yes / no で始まる行を採用する。
            for (rawLine in raw.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                if (line.startsWith("yes")) {
                    return CoverageResult(canAnswer = true, missingInformation = emptyList())
                }
                if (line.startsWith("no")) {
                    val parts = line.substringAfter("no").trimStart(',', ' ')
                    val missing = if (parts.isBlank()) emptyList()
                    else parts.split(",").mapNotNull { it.trim().ifBlank { null } }
                    Timber.tag(TAG).d("coverage=false missing=$missing")
                    return CoverageResult(canAnswer = false, missingInformation = missing)
                }
            }

            // 判定不能時は「回答可能」扱いにして余計なReActを起動しない
            Timber.tag(TAG).d("coverage parse unclear: $raw — defaulting canAnswer=true")
            return CoverageResult(canAnswer = true, missingInformation = emptyList())
        }
    }

    suspend fun check(query: String, candidates: List<Citation>): CoverageResult {
        // 日付クエリで日付プレフィックス付き候補があれば、LLM を呼ばずに即答可能と判定
        if (isDateShortCircuit(query, candidates)) {
            Timber.tag(TAG).d("short-circuit: date query with dated candidate → canAnswer=true")
            return CoverageResult(canAnswer = true, missingInformation = emptyList())
        }

        // 日付クエリ + 固有名詞ヒットでも即答可能扱い（回答 LLM が snippet 本文の日付を拾う）
        if (isTopicMatchShortCircuit(query, candidates)) {
            Timber.tag(TAG).d("short-circuit: date query with topic-match candidate → canAnswer=true")
            return CoverageResult(canAnswer = true, missingInformation = emptyList())
        }

        if (!llmService.isReady()) return CoverageResult(canAnswer = true, missingInformation = emptyList())

        val prompt = buildPrompt(query, candidates.take(MAX_CANDIDATES))
        val sb = StringBuilder()
        runCatching {
            llmService.generateStream(prompt).collect { token -> sb.append(token) }
        }.onFailure {
            Timber.tag(TAG).w(it, "coverage check LLM failed")
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
}
