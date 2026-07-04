package com.minibrain.ai.search

import com.minibrain.ai.llm.LlmService
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

// Hypothetical Document Embeddings (Gao et al. 2022).
// クエリに対する「ありそうな回答（仮想 passage）」を LLM に生成させ、その埋め込みで
// ベクトル検索を行うことで、query↔passage の表現非対称性を緩和する。
//
// LiteRT-LM が単一スレッドであるため、QueryExpander の直後に逐次実行する。
// 失敗・タイムアウトは null を返し、呼び出し側はフォールバック動作にする。
class HyDE(private val llmService: LlmService) {

    companion object {
        private const val TAG = "HyDE"
        private const val GENERATE_TIMEOUT_MS = 6_000L
        private const val MAX_CHARS = 280
    }

    suspend fun generateHypothetical(query: String): String? {
        if (!llmService.isReady()) return null

        val prompt = buildPrompt(query)
        val sb = StringBuilder()
        val ok = withTimeoutOrNull(GENERATE_TIMEOUT_MS) {
            runCatching {
                llmService.generateStream(prompt).collect { token -> sb.append(token) }
                true
            }.onFailure { Timber.tag(TAG).w(it, "HyDE generation failed") }.getOrDefault(false)
        } ?: run {
            Timber.tag(TAG).w("HyDE generation timed out")
            false
        }
        if (!ok) return null

        val cleaned = sb.toString()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_CHARS)
        return cleaned.ifBlank { null }
    }

    private fun buildPrompt(query: String): String = """
        次の質問に対する想定回答を、もしあなたが個人ノートを書く本人だとしたらどう書きそうか、
        1〜2 文の日本語で記述してください。
        - 実在しない固有名詞は作らず、もっともらしい一般表現に留めてください
        - 質問文の主要キーワードを必ず1つ以上含めてください
        - 説明・前置き・記号は不要。本文のみを出力してください

        質問: $query
        想定回答:
    """.trimIndent()
}
