package com.minibrain.ai.agent

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class QueryClassifierTest {

    private val today: LocalDate = LocalDate.of(2025, 6, 15)

    private fun classify(question: String): QueryType =
        QueryClassifier.classify(question, today)

    // --- TEMPORAL_SUMMARIZATION（期間表現が最優先） ---

    @Test
    fun `去年の夏はTEMPORAL`() {
        assertEquals(QueryType.TEMPORAL_SUMMARIZATION, classify("去年の夏は何してた？"))
    }

    @Test
    fun `年月指定はTEMPORAL`() {
        assertEquals(QueryType.TEMPORAL_SUMMARIZATION, classify("2024年3月に何をしていた？"))
    }

    @Test
    fun `元号年はTEMPORAL`() {
        assertEquals(QueryType.TEMPORAL_SUMMARIZATION, classify("令和5年の振り返り"))
    }

    @Test
    fun `期間表現はMEMORYキーワードより優先される`() {
        // 「何してた」は MEMORY キーワードだが「去年」の期間解決が勝つ
        assertEquals(QueryType.TEMPORAL_SUMMARIZATION, classify("去年何してた？"))
    }

    // --- MEMORY_SEARCH ---

    @Test
    fun `昨日何してたはMEMORY`() {
        // 「昨日」は resolveDateRange 対象外（resolveToDateStrings のみ）なので MEMORY に落ちる
        assertEquals(QueryType.MEMORY_SEARCH, classify("昨日何してた？"))
    }

    @Test
    fun `MEMORYキーワードはGENERALパターンより優先される`() {
        // 「の仕組み」「を説明して」は GENERAL パターンだが「日記」が勝つ
        assertEquals(QueryType.MEMORY_SEARCH, classify("日記の仕組みを説明して"))
    }

    @Test
    fun `について教えてはGENERALに分類しない`() {
        // 個人ノートでも多用されるため MEMORY_SEARCH に倒す（CLAUDE.md 注意事項）
        assertEquals(QueryType.MEMORY_SEARCH, classify("量子コンピュータについて教えて"))
    }

    @Test
    fun `分類不能はデフォルトでMEMORY`() {
        assertEquals(QueryType.MEMORY_SEARCH, classify("サウナしきじにいつ行ったっけ？"))
    }

    // --- GENERAL_KNOWLEDGE ---

    @Test
    fun `とは疑問はGENERAL`() {
        assertEquals(QueryType.GENERAL_KNOWLEDGE, classify("HTTPとは？"))
    }

    @Test
    fun `の仕組みはGENERAL`() {
        assertEquals(QueryType.GENERAL_KNOWLEDGE, classify("TCPの仕組み"))
    }

    @Test
    fun `英語識別子の使い方はGENERAL`() {
        assertEquals(QueryType.GENERAL_KNOWLEDGE, classify("Kotlinの使い方を知りたい"))
    }

    // --- dateRange 引数の事前解決 ---

    @Test
    fun `解決済みdateRangeを渡すと再解決せずTEMPORALになる`() {
        val range = DateRange(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 8, 31))
        assertEquals(
            QueryType.TEMPORAL_SUMMARIZATION,
            QueryClassifier.classify("夏の思い出", today, dateRange = range),
        )
    }

    @Test
    fun `dateRangeにnullを明示すると期間解決をスキップする`() {
        // 呼び出し側が「期間なし」と解決済みの場合、内部で再解決しない
        assertEquals(
            QueryType.MEMORY_SEARCH,
            QueryClassifier.classify("去年の夏は何してた？", today, dateRange = null),
        )
    }
}
