package com.minibrain.ai.search

import com.minibrain.ai.llm.LlmService
import timber.log.Timber

class QueryExpander(private val llmService: LlmService) {

    companion object {
        private const val TAG = "QueryExpander"
        private const val MAX_QUERIES = 8
        private val QUOTED_ELEMENT_REGEX = Regex(""""([^"]*)"|'([^']*)'""")

        // 簡易 JSON 配列パーサ。LLM 出力に前置きやコードフェンスが混ざる前提で、
        // 最初に出現する [ から最後の ] までを切り出し、ダブル/シングルクォート両対応で要素を抽出する。
        internal fun parseJsonArray(raw: String): List<String> {
            val start = raw.indexOf('[')
            val end = raw.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val jsonStr = raw.substring(start, end + 1)
            return runCatching {
                val result = mutableListOf<String>()
                QUOTED_ELEMENT_REGEX.findAll(jsonStr).forEach { match ->
                    val value = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (value.isNotBlank()) result += value
                }
                result
            }.getOrElse {
                Timber.tag(TAG).w("JSON parse failed: $it")
                emptyList()
            }
        }
    }

    suspend fun expand(query: String): List<String> {
        if (!llmService.isReady()) return listOf(query)

        val prompt = buildPrompt(query)
        val sb = StringBuilder()
        runCatching {
            llmService.generateStream(prompt).collect { token -> sb.append(token) }
        }.onFailure {
            Timber.tag(TAG).w(it, "LLM failed during expansion")
            return listOf(query)
        }

        val parsed = parseJsonArray(sb.toString())
            .mapNotNull { it.trim().ifBlank { null } }
            .distinct()

        // 元クエリが含まれていない場合は先頭に追加
        val withOriginal = if (parsed.any { it == query }) parsed else listOf(query) + parsed
        val result = withOriginal.take(MAX_QUERIES)
        Timber.tag(TAG).d("expanded: ${result.joinToString(" | ")}")
        return result
    }

    private fun buildPrompt(query: String): String = """
        ユーザーの検索クエリに対して、より多くの関連文書を見つけるための検索語群を生成してください。
        元のクエリを必ず含め、3〜8個の検索語をJSON配列で出力してください。
        重複は不可。説明やコメントは不要で、JSON配列のみ出力してください。

        必須ルール:
        - クエリに含まれる固有名詞（人名・地名・施設名・店名・サービス名・商品名・会社名・略語・カタカナ語の塊）は、必ず助詞や疑問詞を取り除いた「単独の名詞」として 1 件以上含めること。
        - 元のクエリは丸ごと 1 件として含めること（上のルールとは別カウント）。
        - 助詞「に・へ・で・と・は・が・を・の」や疑問詞「いつ・どこ・誰・何・どう」は固有名詞から除外する。

        例:
        入力: TOKIUMについて教えて
        出力: ["TOKIUMについて教えて","TOKIUM","株式会社TOKIUM","TOKIUM 経費精算","TOKIUM インボイス"]

        例:
        入力: サウナしきじにいつ行ったっけ？
        出力: ["サウナしきじにいつ行ったっけ？","サウナしきじ","しきじ","訪問日","初回訪問日","いつ"]

        例:
        入力: 5年前の3月何してた？
        出力: ["5年前の3月何してた？","2021年3月","2021-03","日記","journal","振り返り","5年前"]

        入力: $query
        出力:
    """.trimIndent()
}

