package com.minibrain.ai.agent

import java.time.LocalDate

enum class QueryType {
    MEMORY_SEARCH,
    GENERAL_KNOWLEDGE,
    TEMPORAL_SUMMARIZATION,
}

object QueryClassifier {

    private val MEMORY_KEYWORDS = listOf(
        "何してた", "何した", "何やってた", "何やった",
        "覚えてる", "覚えている", "思い出",
        "日記", "メモ", "ノート", "記録", "まとめ",
        "書いた", "書いてた", "作った", "作ってた",
        "自分", "私", "僕", "俺", "うち",
        "振り返り", "振り返る",
    )

    // 一般知識を問う明確なパターン（誤分類を避けるため厳しめに設定）
    private val GENERAL_KNOWLEDGE_PATTERNS = listOf(
        Regex("""とは[何なに]?[？?]?$"""),
        Regex("""の仕組み"""),
        Regex("""なぜ.{0,10}[？?]"""),
        Regex("""どういう意味"""),
        Regex("""の意味は"""),
        Regex("""について教えて"""),
        Regex("""を説明して"""),
        Regex("""[A-Za-z][A-Za-z0-9_]{2,}\s*(と(は|って)|の使い方|の書き方)"""), // 英語識別子 + 使い方/書き方
    )

    fun classify(question: String, today: LocalDate = LocalDate.now()): QueryType {
        // 1. 期間表現があれば TEMPORAL_SUMMARIZATION
        if (DateResolver.resolveDateRange(question, today) != null) {
            return QueryType.TEMPORAL_SUMMARIZATION
        }

        // 2. 個人記録参照語があれば MEMORY_SEARCH
        if (MEMORY_KEYWORDS.any { question.contains(it) } || DateResolver.isDiaryQuery(question)) {
            return QueryType.MEMORY_SEARCH
        }

        // 3. 一般知識パターンに明確にマッチ → GENERAL_KNOWLEDGE
        if (GENERAL_KNOWLEDGE_PATTERNS.any { it.containsMatchIn(question) }) {
            return QueryType.GENERAL_KNOWLEDGE
        }

        // 4. デフォルトは MEMORY_SEARCH（誤分類コストが低い側）
        return QueryType.MEMORY_SEARCH
    }
}
