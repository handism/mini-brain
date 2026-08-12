package com.minibrain.ai.search

import com.minibrain.ai.agent.DateResolver
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.util.DatePrefix
import timber.log.Timber

class LlmReranker(private val llmService: LlmService) {

    companion object {
        private const val TAG = "LlmReranker"
        private const val SNIPPET_MAX_CHARS = 140
        private const val CANDIDATE_LIMIT = 30
        private const val DEFAULT_TOP_K = 10
        private val DIGITS_REGEX = Regex("""\d+""")
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
            Timber.tag(TAG).w(it, "LLM rerank failed")
            return candidates.take(topK)
        }

        val indices = parseIndices(sb.toString())
        Timber.tag(TAG).d("rerank indices=$indices from ${limited.size} candidates")

        if (indices.isEmpty()) return candidates.take(topK)

        val reranked = indices
            .mapNotNull { limited.getOrNull(it) }
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

    private fun buildPrompt(query: String, candidates: List<Citation>, topK: Int): String {
        val sb = StringBuilder()
        sb.appendLine("以下の検索候補から、クエリに最も関連する上位${topK}件のインデックスを関連度の高い順にJSON配列で出力してください。")
        sb.appendLine("説明やコメントは不要で、JSON配列のみ出力してください。")
        sb.appendLine("判断材料: path（ファイル位置）/ heading（見出し階層）/ date（文書日付があれば）/ topic（ファイル名がクエリと一致したか）/ source（METADATA は完全一致、VECTOR は意味類似）/ snippet（本文抜粋）を総合して関連度を採点してください。")
        if (DateResolver.isDateQuery(query)) {
            // date フィールドを優先しつつ、topic=match の候補は本文中に日付表記があるケースを
            // 想定して同等以上に扱う（ADR-026）。「サウナしきじにいつ行ったっけ」のような固有名詞 +
            // 「いつ」クエリで、date 欄が空のファイル名一致候補が押し出されないようにする。
            sb.appendLine("「いつ」に関する質問のため、date フィールドを持つ候補と topic=match の候補をどちらも上位に残してください。topic=match の候補は date 欄が空でも snippet 本文に日付が書かれている可能性が高いので、date 無しを理由に除外しないでください。")
        }
        sb.appendLine()
        sb.appendLine("クエリ: \"$query\"")
        sb.appendLine()
        candidates.forEachIndexed { i, c ->
            val (date, rest) = DatePrefix.split(c.snippet)
            val body = rest.take(SNIPPET_MAX_CHARS).replace('\n', ' ')
            val path = c.relativePath ?: "?"
            val source = c.source.name
            val datePart = if (date != null) " date=$date" else ""
            val topicPart = if (c.topicMatch) " topic=match" else ""
            sb.appendLine("[$i] path=$path heading=\"${c.headingPath}\"$datePart$topicPart source=$source snippet=$body")
        }
        sb.appendLine()
        sb.appendLine("出力（JSON配列のみ、例: [3, 0, 7, ...]）:")
        return sb.toString()
    }

    private fun parseIndices(raw: String): List<Int> {
        // 出力中から [...] を抽出
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val jsonStr = raw.substring(start, end + 1)

        return runCatching {
            val result = mutableListOf<Int>()
            DIGITS_REGEX.findAll(jsonStr).forEach {
                result += it.value.toInt()
            }
            result
        }.getOrElse {
            Timber.tag(TAG).w("index parse failed: $it")
            emptyList()
        }
    }
}
