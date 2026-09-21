package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import com.minibrain.util.DatePrefix
import com.minibrain.util.PromptUtils
import com.minibrain.util.TokenEstimator

/** 回答生成プロンプトの入力一式。 */
data class AnswerContext(
    val question: String,
    val citations: List<Citation>,
    val history: List<Pair<String, String>>,
    val dateRange: DateRange? = null,
)

/**
 * 回答 LLM に渡すプロンプトを組み立てる。
 * 「知識ベース」ブロック + 日付指示（ADR-025 / ADR-026） + 会話履歴 + 質問 の 4 段構成。
 * AgentPipeline から切り出し、プロンプト文言の変更がパイプライン制御と混ざらないようにしている。
 */
object AnswerPromptBuilder {

    private fun buildContextBlock(citations: List<Citation>): String {
        return if (citations.isNotEmpty()) {
            val budgeted = mutableListOf<Citation>()
            var remainingTokens = TokenEstimator.MAX_CONTEXT_TOKENS
            for (c in citations) {
                val cost = TokenEstimator.estimate(c.headingPath, c.snippet)
                if (remainingTokens <= 0) break
                budgeted += c
                remainingTokens -= cost
            }
            val body = budgeted.joinToString("\n\n") { c ->
                val pathPrefix = c.relativePath?.let { "$it > " } ?: ""
                "### $pathPrefix${c.headingPath}\n${c.snippet}"
            }
            """あなたはユーザーのパーソナルアシスタントです。以下の「知識ベース」を参考にして質問に答えてください。
知識ベースにある情報を優先し、不足時は一般知識で補足（その際は明記）してください。

知識ベース:
$body

---"""
        } else {
            "知識ベースに関連する情報が見つかりませんでした。一般的な知識で回答してください。\n\n---"
        }
    }

    private fun buildTemporalInstruction(context: AnswerContext): String {
        // 日付関連の指示。dateRange があれば期間照合、無くても「いつ」系クエリなら本文中の日付を
        // 拾うよう誘導する（ADR-026）。snippet から日付を抽出する優先順位は次の通り:
        //   1. `[日付: YYYY-MM-DD]` プレフィックス（システム抽出済みの documentDate）
        //   2. 本文中の「初回訪問日: …」「訪問日: …」「日付: …」のようなラベル行
        //   3. 本文中の YYYY/MM/DD / YYYY-MM-DD / YYYY年MM月DD日 表記
        val isDateQuery = DateResolver.isDateQuery(context.question)
        val dateRange = context.dateRange
        return when {
            dateRange != null -> {
                val hasDated = context.citations.any { DatePrefix.hasPrefix(it.snippet) }
                if (hasDated) {
                    """
【期間クエリの解釈】
質問内の「去年」「先月」「今年の冬」などの相対表現は、システム側で既に **${dateRange.start} 〜 ${dateRange.end}** の期間に解釈済みです。
年号の解釈で迷ったり「どの年を指すか不明」などと逡巡せず、この期間を所与の前提として回答してください。

【回答の作り方】
1. 各 snippet から日付を拾う優先順位:
   - 先頭の `[日付: YYYY-MM-DD]` プレフィックス（システム抽出済みのファイル日付）
   - 本文中の「初回訪問日: …」「訪問日: …」「日付: …」のようなラベル行
   - 本文中の YYYY/MM/DD・YYYY-MM-DD・YYYY年MM月DD日 表記
2. 拾った日付を上記期間と照合し、該当する snippet を時系列順に整理して具体的に答える
3. snippet 本文に活動内容が書かれていれば、それを「情報がない」と切り捨てず素直に紹介する
""".trimIndent()
                } else {
                    """
【期間クエリの解釈】
質問内の相対表現は ${dateRange.start} 〜 ${dateRange.end} の期間に解釈済みです。
`[日付:]` プレフィックス付き候補は見つかりませんでしたが、snippet 本文中の「初回訪問日: …」「訪問日: …」ラベル行や、YYYY/MM/DD・YYYY年MM月DD日 表記も日付として有効です。これらを期間と照合してください。
該当する日付が一切見つからなければ、その旨を率直に伝えたうえで、関連しそうな snippet を補足として提示してください。
""".trimIndent()
                }
            }
            isDateQuery -> {
                """
【日付に関する質問】
質問は時期・日付を尋ねています。snippet から日付を拾う優先順位:
1. 先頭の `[日付: YYYY-MM-DD]` プレフィックス（システム抽出済みのファイル日付）
2. 本文中の「初回訪問日: …」「訪問日: …」「日付: …」のようなラベル行
3. 本文中の YYYY/MM/DD・YYYY-MM-DD・YYYY年MM月DD日 表記

該当ファイル名がクエリに含まれている場合は、そのファイルの本文中の日付を主な根拠として「いつ」かを具体的に答えてください。日付らしき表記が無ければ、その旨を率直に伝えてください。
""".trimIndent()
            }
            else -> ""
        }
    }

    fun buildDirectAnswerPrompt(
        question: String,
        history: List<Pair<String, String>>,
    ): String {
        val historyBlock = PromptUtils.renderHistoryBlock(history)
        return "${historyBlock}ユーザー: $question\nアシスタント:"
    }


    fun buildAnswerPrompt(
        context: AnswerContext
    ): String {
        val contextBlock = buildContextBlock(context.citations)
        val temporalInstruction = buildTemporalInstruction(context)
        val historyBlock = PromptUtils.renderHistoryBlock(context.history)

        val temporalBlock = if (temporalInstruction.isNotEmpty()) "$temporalInstruction\n\n" else ""
        return "$contextBlock\n\n$temporalBlock$historyBlock\nユーザー: ${context.question}\nアシスタント:"
    }
}
