package com.minibrain.ai.agent

import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageCheckerTest {

    private val llmService = mockk<LlmService>()
    private val coverageChecker = CoverageChecker(llmService)

    private fun citation(snippet: String, topicMatch: Boolean = false) = Citation(headingPath = "note.md", snippet = snippet, topicMatch = topicMatch)

    // --- isTopicMatchShortCircuit ---

    @Test
    fun `日付クエリかつtopicMatch付き候補で短絡する`() {
        val candidates = listOf(citation("サウナしきじを訪問", topicMatch = true))
        assertTrue(CoverageChecker.isTopicMatchShortCircuit("サウナしきじにいつ行った？", candidates))
    }

    @Test
    fun `日付クエリでもtopicMatchがなければ短絡しない`() {
        val candidates = listOf(citation("サウナしきじは静岡の有名サウナ", topicMatch = false))
        assertFalse(CoverageChecker.isTopicMatchShortCircuit("サウナしきじにいつ行った？", candidates))
    }

    @Test
    fun `日付クエリでなければtopicMatch付き候補があっても短絡しない`() {
        val candidates = listOf(citation("サウナしきじを訪問", topicMatch = true))
        assertFalse(CoverageChecker.isTopicMatchShortCircuit("サウナしきじについてまとめて", candidates))
    }

    @Test
    fun `上位5件より後ろのtopicMatch付き候補は短絡対象外`() {
        val candidates = List(5) { citation("topicMatchなし候補 $it", topicMatch = false) } +
            citation("訪問記録", topicMatch = true)
        assertFalse(CoverageChecker.isTopicMatchShortCircuit("いつ行った？", candidates))
    }

    // --- isDateShortCircuit ---

    @Test
    fun `日付クエリかつ日付プレフィックス付き候補で短絡する`() {
        val candidates = listOf(citation("[日付: 2024-05-01] サウナしきじを訪問"))
        assertTrue(CoverageChecker.isDateShortCircuit("サウナしきじにいつ行った？", candidates))
    }

    @Test
    fun `snippet先頭の空白は無視される`() {
        val candidates = listOf(citation("  [日付: 2024-05-01] 訪問記録"))
        assertTrue(CoverageChecker.isDateShortCircuit("いつ行った？", candidates))
    }

    @Test
    fun `日付クエリでも日付プレフィックスがなければ短絡しない`() {
        val candidates = listOf(citation("サウナしきじは静岡の有名サウナ"))
        assertFalse(CoverageChecker.isDateShortCircuit("サウナしきじにいつ行った？", candidates))
    }

    @Test
    fun `日付クエリでなければ日付付き候補があっても短絡しない`() {
        val candidates = listOf(citation("[日付: 2024-05-01] サウナしきじを訪問"))
        assertFalse(CoverageChecker.isDateShortCircuit("サウナしきじについてまとめて", candidates))
    }

    @Test
    fun `上位5件より後ろの日付付き候補は短絡対象外`() {
        val candidates = List(5) { citation("日付なし候補 $it") } +
            citation("[日付: 2024-05-01] 訪問記録")
        assertFalse(CoverageChecker.isDateShortCircuit("いつ行った？", candidates))
    }

    // --- parse ---

    @Test
    fun `yesはcanAnswer=true`() {
        val result = CoverageChecker.parse("yes")
        assertTrue(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }

    @Test
    fun `noと不足キーワードを解析する`() {
        val result = CoverageChecker.parse("no, visit_date")
        assertFalse(result.canAnswer)
        assertEquals(listOf("visit_date"), result.missingInformation)
    }

    @Test
    fun `複数の不足キーワードを解析する`() {
        val result = CoverageChecker.parse("no, event_date, location")
        assertFalse(result.canAnswer)
        assertEquals(listOf("event_date", "location"), result.missingInformation)
    }

    @Test
    fun `noのみでも不足キーワード空でcanAnswer=false`() {
        val result = CoverageChecker.parse("no")
        assertFalse(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }

    @Test
    fun `先頭の空行をスキップして最初の行を解析する`() {
        val result = CoverageChecker.parse("\n\nno, x")
        assertFalse(result.canAnswer)
        assertEquals(listOf("x"), result.missingInformation)
    }

    @Test
    fun `判定不能な出力はcanAnswer=trueにフォールバック`() {
        assertTrue(CoverageChecker.parse("わかりません").canAnswer)
    }

    @Test
    fun `空文字はcanAnswer=trueにフォールバック`() {
        assertTrue(CoverageChecker.parse("").canAnswer)
    }

    // --- check ---

    @Test
    fun `check - 日付クエリかつ日付プレフィックス付き候補で短絡する`() = runTest {
        val candidates = listOf(citation("[日付: 2024-05-01] サウナしきじを訪問"))
        val result = coverageChecker.check("サウナしきじにいつ行った？", candidates)
        assertTrue(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }

    @Test
    fun `check - 日付クエリかつtopicMatch付き候補で短絡する`() = runTest {
        val candidates = listOf(citation("サウナしきじを訪問", topicMatch = true))
        val result = coverageChecker.check("サウナしきじにいつ行った？", candidates)
        assertTrue(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }

    @Test
    fun `check - LLMの準備ができていない場合はcanAnswer=trueにフォールバック`() = runTest {
        val candidates = listOf(citation("サウナしきじは静岡の有名サウナ"))
        every { llmService.isReady() } returns false

        val result = coverageChecker.check("サウナしきじの魅力は？", candidates)
        assertTrue(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }

    @Test
    fun `check - LLMがyesを返した場合はcanAnswer=true`() = runTest {
        val candidates = listOf(citation("サウナしきじは静岡の有名サウナ"))
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flowOf("y", "e", "s")

        val result = coverageChecker.check("サウナしきじの魅力は？", candidates)
        assertTrue(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }

    @Test
    fun `check - LLMがnoと不足キーワードを返した場合はcanAnswer=false`() = runTest {
        val candidates = listOf(citation("サウナしきじは静岡の有名サウナ"))
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flowOf("no", ", ", "location", ", ", "price")

        val result = coverageChecker.check("サウナしきじの場所と料金は？", candidates)
        assertFalse(result.canAnswer)
        assertEquals(listOf("location", "price"), result.missingInformation)
    }

    @Test
    fun `check - LLMの生成で例外が発生した場合はcanAnswer=trueにフォールバック`() = runTest {
        val candidates = listOf(citation("サウナしきじは静岡の有名サウナ"))
        every { llmService.isReady() } returns true
        every { llmService.generateStream(any()) } returns flow { throw RuntimeException("LLM error") }

        val result = coverageChecker.check("サウナしきじの魅力は？", candidates)
        assertTrue(result.canAnswer)
        assertTrue(result.missingInformation.isEmpty())
    }
}
