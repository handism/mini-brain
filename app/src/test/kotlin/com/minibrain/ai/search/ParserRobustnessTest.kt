package com.minibrain.ai.search

import com.minibrain.ai.agent.CoverageChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserRobustnessTest {

    // --- QueryExpander.parseJsonArray ---

    @Test
    fun `parseJsonArray は会話的前置きが付いた配列を抽出する`() {
        val raw = """
            Certainly! Here are the search queries:
            ["query1", "query2", "query3"]
            Hope this helps!
        """.trimIndent()
        assertEquals(listOf("query1", "query2", "query3"), QueryExpander.parseJsonArray(raw))
    }

    @Test
    fun `parseJsonArray はコードフェンス内の配列を抽出する`() {
        val raw = """
            ```json
            ["query A", "query B"]
            ```
        """.trimIndent()
        assertEquals(listOf("query A", "query B"), QueryExpander.parseJsonArray(raw))
    }

    @Test
    fun `parseJsonArray はシングルクォート要素も抽出する`() {
        val raw = "I found these: ['query single', 'another item']"
        assertEquals(listOf("query single", "another item"), QueryExpander.parseJsonArray(raw))
    }

    @Test
    fun `parseJsonArray は配列が存在しなければ空リスト`() {
        assertTrue(QueryExpander.parseJsonArray("no array here").isEmpty())
    }

    // --- CoverageChecker.parse（複数行スキャン） ---
    // parse() の入力は check() 側で trim + lowercase 済み前提なので、ここでも小文字で渡す。

    @Test
    fun `parse は前置きの後にある yes 行を採用する`() {
        val raw = "after reviewing, the answer is:\nyes, we have enough info."
        val result = CoverageChecker.parse(raw)
        assertTrue(result.canAnswer)
    }

    @Test
    fun `parse は前置きの後にある no 行を採用する`() {
        val raw = "i think the answer is unclear.\nno, missing_date, missing_location"
        val result = CoverageChecker.parse(raw)
        assertFalse(result.canAnswer)
        assertEquals(listOf("missing_date", "missing_location"), result.missingInformation)
    }

    @Test
    fun `parse は yes と no が混在する場合は最初に出てきた方を採用する`() {
        val raw = "yes\nno, ignored"
        val result = CoverageChecker.parse(raw)
        assertTrue(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }
}
