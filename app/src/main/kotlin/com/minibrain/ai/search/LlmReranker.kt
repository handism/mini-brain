package com.minibrain.ai.search

import android.util.Log
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation

class LlmReranker(private val llmService: LlmService) {

    companion object {
        private const val TAG = "LlmReranker"
        private const val SNIPPET_MAX_CHARS = 100
        private const val CANDIDATE_LIMIT = 30
        private const val DEFAULT_TOP_K = 10
    }

    suspend fun rerank(
        query: String,
        candidates: List<Citation>,
        topK: Int = DEFAULT_TOP_K,
    ): List<Citation> {
        if (candidates.size <= topK) return candidates
        if (!llmService.isReady()) return candidates.take(topK)

        val limited = candidates.take(CANDIDATE_LIMIT)
        val prompt = buildPrompt(query, limited, topK)
        val sb = StringBuilder()
        runCatching {
            llmService.generateStream(prompt).collect { token -> sb.append(token) }
        }.onFailure {
            Log.w(TAG, "LLM rerank failed", it)
            return candidates.take(topK)
        }

        val indices = parseIndices(sb.toString())
        Log.d(TAG, "rerank indices=$indices from ${limited.size} candidates")

        if (indices.isEmpty()) return candidates.take(topK)

        val reranked = indices
            .filter { it in limited.indices }
            .map { limited[it] }
            .take(topK)

        // インデックスが足りない場合は元順で補完
        return if (reranked.size >= topK) {
            reranked
        } else {
            val used = reranked.map { it.headingPath + it.docId }.toSet()
            val supplement = candidates.filter { (it.headingPath + it.docId) !in used }
            (reranked + supplement).take(topK)
        }
    }

    private fun isDateQuery(query: String): Boolean =
        Regex("""いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで""").containsMatchIn(query)

    private fun buildPrompt(query: String, candidates: List<Citation>, topK: Int): String {
        val sb = StringBuilder()
        sb.appendLine("以下の検索候補から、クエリに最も関連する上位${topK}件のインデックスを関連度の高い順にJSON配列で出力してください。")
        sb.appendLine("説明やコメントは不要で、JSON配列のみ出力してください。")
        if (isDateQuery(query)) {
            sb.appendLine("「いつ」に関する質問のため、日付（YYYY-MM-DD, 年月日形式等）を含む候補を優先してください。")
        }
        sb.appendLine()
        sb.appendLine("クエリ: \"$query\"")
        sb.appendLine()
        candidates.forEachIndexed { i, c ->
            val snippet = c.snippet.take(SNIPPET_MAX_CHARS).replace('\n', ' ')
            sb.appendLine("[$i] ${c.headingPath}: $snippet")
        }
        sb.appendLine()
        sb.appendLine("出力（JSON配列のみ、例: [3, 0, 7, ...]）:")
        return sb.toString()
    }

    private fun parseIndices(raw: String): List<Int> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val jsonStr = raw.substring(start, end + 1)
        return runCatching {
            val result = mutableListOf<Int>()
            val regex = Regex("""\d+""")
            regex.findAll(jsonStr).forEach {
                result += it.value.toInt()
            }
            result
        }.getOrElse {
            Log.w(TAG, "index parse failed: $it")
            emptyList()
        }
    }
}
