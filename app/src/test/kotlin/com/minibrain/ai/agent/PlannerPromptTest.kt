package com.minibrain.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerPromptTest {

    @Test
    fun parseDecision_returnsGlobCall() {
        val raw = "TOOL: glob\nPATTERN: 2026/06/*"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.Glob)
        assertEquals("2026/06/*", (tool as AgentTool.Glob).pattern)
    }

    @Test
    fun parseDecision_returnsReadFileWithDocId() {
        val raw = "TOOL: read_file\nDOC_ID: 12"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.ReadFile)
        assertEquals(12L, (tool as AgentTool.ReadFile).docId)
    }

    @Test
    fun parseDecision_returnsReadFileWithPath() {
        val raw = "TOOL: read_file\nPATH: proj/設計メモ.md"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.ReadFile
        assertEquals("proj/設計メモ.md", tool.path)
        assertNull(tool.docId)
    }

    @Test
    fun parseDecision_returnsFinalize() {
        val raw = "ACTION: finalize\nREASON: enough info"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Finalize)
        assertEquals("enough info", (decision as PlannerDecision.Finalize).reason)
    }

    @Test
    fun parseDecision_returnsParseErrorForUnknownTool() {
        val raw = "TOOL: unknown_tool\nQUERY: something"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun parseDecision_handlesNoiseBeforeKeys() {
        val raw = "Sure! Here is my decision:\nTOOL: list_dir\nFOLDER: proj"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.ListDir)
        assertEquals("proj", (tool as AgentTool.ListDir).folder)
    }

    @Test
    fun parseDecision_returnsParseErrorForEmptyString() {
        val decision = PlannerPrompt.parseDecision("")
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun parseDecision_returnsGrepWithScope() {
        val raw = "TOOL: grep\nQUERY: コスト削減\nSCOPE: proj/"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.Grep
        assertEquals("コスト削減", tool.query)
        assertEquals("proj/", tool.scope)
    }

    @Test
    fun parseDecision_returnsVectorSearchWithK() {
        val raw = "TOOL: vector_search\nQUERY: 機械学習\nK: 15"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.VectorSearch
        assertEquals("機械学習", tool.query)
        assertEquals(15, tool.k)
    }

    @Test
    fun parseDecision_returnsRrfSearch() {
        val raw = "TOOL: rrf_search\nQUERY: 読書記録"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.RrfSearch
        assertEquals("読書記録", tool.query)
        assertEquals(10, tool.k)
    }

    @Test
    fun parseDecision_returnsTimelineSearch() {
        val raw = "TOOL: timeline_search\nSTART: 2025-06-01\nEND: 2025-08-31\nLIMIT: 15"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.TimelineSearch
        assertEquals("2025-06-01", tool.startDate)
        assertEquals("2025-08-31", tool.endDate)
        assertEquals(15, tool.limit)
    }

    @Test
    fun parseDecision_handlesLowercaseKeys() {
        val raw = "tool: grep\nquery: AWS"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.Grep
        assertEquals("AWS", tool.query)
    }

    @Test
    fun parseDecision_returnsParseErrorWhenToolKeyMissing() {
        val raw = "QUERY: something"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun parseDecision_returnsParseErrorWhenTimelineSearchMissingRequiredKeys() {
        val raw = "TOOL: timeline_search\nSTART: 2025-06-01"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun buildReturnsBasicPromptWithNoObservationsAndNoHint() {
        val prompt = PlannerPrompt.build(
            question = "What is the capital of Japan?",
            plannerHint = null,
            observations = emptyList()
        )

        assertTrue(prompt.contains("質問: What is the capital of Japan?"))
        assertTrue(prompt.contains("観測: なし"))
        assertTrue(prompt.contains("【重要】観測がまだ0件です。必ずツールを1回実行してください。今は finalize 禁止。"))
        assertTrue(!prompt.contains("ヒント:"))
    }

    @Test
    fun buildIncludesHintWhenPlannerHintIsProvided() {
        val prompt = PlannerPrompt.build(
            question = "Test",
            plannerHint = "Look in the docs folder",
            observations = emptyList()
        )

        assertTrue(prompt.contains("ヒント: Look in the docs folder"))
    }

    @Test
    fun buildIncludesFormattedObservationsAndAllowsFinalize() {
        val obs1 = Observation(ToolCall(1, AgentTool.Glob("*.md")), "Matched 2 files", full = false)
        val obs2 = Observation(ToolCall(2, AgentTool.ReadFile(docId = 10L, path = null)), "File content here", full = true)

        val prompt = PlannerPrompt.build(
            question = "Test",
            plannerHint = null,
            observations = listOf(obs1, obs2)
        )

        assertTrue(prompt.contains("情報が集まったら finalize を選択してください。"))
        assertTrue(prompt.contains("OBS#1 glob(\"*.md\") → Matched 2 files"))
        assertTrue(prompt.contains("OBS#2 read_file(docId=10):"))
        assertTrue(prompt.contains("File content here"))
    }

    @Test
    fun buildTruncatesLargeObservationText() {
        val longText = "A".repeat(2000)
        val obs = Observation(ToolCall(1, AgentTool.ReadFile(null, "large.txt")), longText, full = true)

        val prompt = PlannerPrompt.build(
            question = "Test",
            plannerHint = null,
            observations = listOf(obs)
        )

        assertTrue(prompt.contains("read_file(\"large.txt\"):"))
        assertTrue(prompt.contains("A".repeat(1500)))
        assertTrue(prompt.contains("...[truncated]"))
    }

    @Test
    fun buildTruncatesTotalFormattedObservationsOver5000Chars() {
        val longText = "A".repeat(1500)
        val obs1 = Observation(ToolCall(1, AgentTool.ReadFile(1L, null)), "1" + longText, full = true)
        val obs2 = Observation(ToolCall(2, AgentTool.ReadFile(2L, null)), "2" + longText, full = true)
        val obs3 = Observation(ToolCall(3, AgentTool.ReadFile(3L, null)), "3" + longText, full = true)
        val obs4 = Observation(ToolCall(4, AgentTool.ReadFile(4L, null)), "4" + longText, full = true)

        val prompt = PlannerPrompt.build(
            question = "Test",
            plannerHint = null,
            observations = listOf(obs1, obs2, obs3, obs4)
        )

        // formatObservations uses takeLast(5000). The first observation will be cut out, but the last one should be there.
        assertTrue(prompt.contains("4" + "A".repeat(1400)))

        val observationsStr = prompt.substringAfter("観測:\n").substringBefore("\n情報が集まったら")
        assertTrue("length is ${'$'}{observationsStr.length}", observationsStr.length <= 5005)
    }

    @Test
    fun buildToolCallDescriptionFormatChecks() {
        val obsList = listOf(
            Observation(ToolCall(1, AgentTool.Glob("*.txt")), "glob_test", false),
            Observation(ToolCall(2, AgentTool.ListDir("docs/")), "list_test", false),
            Observation(ToolCall(3, AgentTool.ReadFile(docId = null, path = null)), "read_empty", false),
            Observation(ToolCall(4, AgentTool.Grep("query", null)), "grep_no_scope", false),
            Observation(ToolCall(5, AgentTool.VectorSearch("q", null, 10)), "vec_test", false),
            Observation(ToolCall(6, AgentTool.RrfSearch("q", 10)), "rrf_test", false),
            Observation(ToolCall(7, AgentTool.TimelineSearch("2023-01-01", "2023-12-31", 10)), "timeline_test", false)
        )

        val prompt = PlannerPrompt.build("Test", null, obsList)

        assertTrue(prompt.contains("glob(\"*.txt\")"))
        assertTrue(prompt.contains("list_dir(\"docs/\")"))
        assertTrue(prompt.contains("read_file(?)"))
        assertTrue(prompt.contains("grep(\"query\")"))
        assertTrue(prompt.contains("vector_search(\"q\", k=10)"))
        assertTrue(prompt.contains("rrf_search(\"q\", k=10)"))
        assertTrue(prompt.contains("timeline_search(\"2023-01-01\"~\"2023-12-31\", limit=10)"))
    }
}
