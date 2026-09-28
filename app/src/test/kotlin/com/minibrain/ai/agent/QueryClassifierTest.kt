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

    @Test
    fun `期間表現はGENERALパターンより優先される`() {
        // 「とは？」は GENERAL パターンだが「去年」の期間解決が勝つ
        assertEquals(QueryType.TEMPORAL_SUMMARIZATION, classify("去年とは？"))
    }

    @Test
    fun `すべての条件を満たす場合はTEMPORALが最優先される`() {
        // 期間(去年) + MEMORY(日記) + GENERAL(仕組み)
        assertEquals(QueryType.TEMPORAL_SUMMARIZATION, classify("去年の日記の仕組み"))
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
        assertEquals(QueryType.MEMORY_SEARCH, classify("スパイス堂にいつ行ったっけ？"))
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
    fun `なぜ疑問はGENERAL`() {
        assertEquals(QueryType.GENERAL_KNOWLEDGE, classify("空はなぜ青い？"))
    }

    @Test
    fun `どういう意味はGENERAL`() {
        assertEquals(QueryType.GENERAL_KNOWLEDGE, classify("忖度ってどういう意味？"))
    }

    @Test
    fun `の意味はGENERAL`() {
        assertEquals(QueryType.GENERAL_KNOWLEDGE, classify("パラダイムシフトの意味は？"))
    }

    @Test
    fun `説明してはGENERAL`() {
        assertEquals(QueryType.GENERAL_KNOWLEDGE, classify("相対性理論を説明して"))
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
