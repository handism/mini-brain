package com.minibrain.ai.search

import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.rag.Citation
import com.minibrain.ai.agent.CoverageChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

class ParserRobustnessTest {

    @Test
    fun `QueryExpander parseJsonArray should handle conversational text and code blocks`() {
        val expander = QueryExpander(FakeLlmService("dummy"))

        val raw1 = """
            Certainly! Here are the search queries:
            ["query1", "query2", "query3"]
            Hope this helps!
        """.trimIndent()
        val res1 = expander.callParseJsonArray(raw1)
        assertEquals(listOf("query1", "query2", "query3"), res1)

        val raw2 = """
            ```json
            ["query A", "query B"]
            ```
        """.trimIndent()
        val res2 = expander.callParseJsonArray(raw2)
        assertEquals(listOf("query A", "query B"), res2)

        val raw3 = """
            I found these: ['query single', 'query "double"']
        """.trimIndent()
        val res3 = expander.callParseJsonArray(raw3)
        assertEquals(listOf("query single", "query \"double\""), res3)
    }

    @Test
    fun `LlmReranker parseIndices should handle conversational text and code blocks`() {
        val reranker = LlmReranker(FakeLlmService("dummy"))

        val raw1 = "Top results are: [2, 0, 5]"
        val res1 = reranker.callParseIndices(raw1)
        assertEquals(listOf(2, 0, 5), res1)

        val raw2 = """
            ```
            [10, 3, 1]
            ```
        """.trimIndent()
        val res2 = reranker.callParseIndices(raw2)
        assertEquals(listOf(10, 3, 1), res2)
    }

    @Test
    fun `CoverageChecker parse should find yes or no in conversational text`() {
        val checker = CoverageChecker(FakeLlmService("dummy"))

        val raw1 = "After reviewing, the answer is: yes, we have enough info."
        val res1 = checker.callParse(raw1)
        assertTrue(res1.canAnswer)

        val raw2 = """
            I think the answer is no.
            no, missing_date, missing_location
        """.trimIndent()
        val res2 = checker.callParse(raw2)
        assertEquals(false, res2.canAnswer)
        assertEquals(listOf("missing_date", "missing_location"), res2.missingInformation)
    }

    private class FakeLlmService(val response: String) : LlmService(null) {
        override fun isReady() = true
        override fun generateStream(prompt: String) = flowOf(response)
    }

    // Helper to call private parse methods via reflection or by making them internal in a real scenario.
    // For this test in the sandbox, I'll use a trick or just assume I can call them if I change visibility.
    // Since I can't easily change visibility of many files at once without a lot of edits,
    // I will use reflection for this verification test.

    private fun QueryExpander.callParseJsonArray(raw: String): List<String> {
        val method = this.javaClass.getDeclaredMethod("parseJsonArray", String::class.java)
        method.isAccessible = true
        return method.invoke(this, raw) as List<String>
    }

    private fun LlmReranker.callParseIndices(raw: String): List<Int> {
        val method = this.javaClass.getDeclaredMethod("parseIndices", String::class.java)
        method.isAccessible = true
        return method.invoke(this, raw) as List<Int>
    }

    private fun CoverageChecker.callParse(raw: String): com.minibrain.ai.agent.CoverageResult {
        val method = this.javaClass.getDeclaredMethod("parse", String::class.java)
        method.isAccessible = true
        return method.invoke(this, raw) as com.minibrain.ai.agent.CoverageResult
    }
}
