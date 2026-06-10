package com.minibrain.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerPromptTest {

    @Test
    fun `parseDecision returns Glob call`() {
        val raw = "TOOL: glob\nPATTERN: 2026/06/*"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.Glob)
        assertEquals("2026/06/*", (tool as AgentTool.Glob).pattern)
    }

    @Test
    fun `parseDecision returns ReadFile with docId`() {
        val raw = "TOOL: read_file\nDOC_ID: 12"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.ReadFile)
        assertEquals(12L, (tool as AgentTool.ReadFile).docId)
    }

    @Test
    fun `parseDecision returns ReadFile with path`() {
        val raw = "TOOL: read_file\nPATH: proj/設計メモ.md"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.ReadFile
        assertEquals("proj/設計メモ.md", tool.path)
        assertNull(tool.docId)
    }

    @Test
    fun `parseDecision returns Finalize`() {
        val raw = "ACTION: finalize\nREASON: enough info"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Finalize)
        assertEquals("enough info", (decision as PlannerDecision.Finalize).reason)
    }

    @Test
    fun `parseDecision returns ParseError for unknown tool`() {
        val raw = "TOOL: unknown_tool\nQUERY: something"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun `parseDecision handles noise before keys`() {
        val raw = "Sure! Here is my decision:\nTOOL: list_dir\nFOLDER: proj"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.ListDir)
        assertEquals("proj", (tool as AgentTool.ListDir).folder)
    }

    @Test
    fun `parseDecision returns ParseError for empty string`() {
        val decision = PlannerPrompt.parseDecision("")
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun `parseDecision returns Grep with scope`() {
        val raw = "TOOL: grep\nQUERY: コスト削減\nSCOPE: proj/"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.Grep
        assertEquals("コスト削減", tool.query)
        assertEquals("proj/", tool.scope)
    }

    @Test
    fun `parseDecision returns VectorSearch with k`() {
        val raw = "TOOL: vector_search\nQUERY: 機械学習\nK: 15"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.VectorSearch
        assertEquals("機械学習", tool.query)
        assertEquals(15, tool.k)
    }

    @Test
    fun `parseDecision returns RrfSearch`() {
        val raw = "TOOL: rrf_search\nQUERY: 読書記録"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.RrfSearch
        assertEquals("読書記録", tool.query)
        assertEquals(10, tool.k)
    }

    @Test
    fun `parseDecision returns TimelineSearch`() {
        val raw = "TOOL: timeline_search\nSTART: 2025-06-01\nEND: 2025-08-31\nLIMIT: 15"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.TimelineSearch
        assertEquals("2025-06-01", tool.startDate)
        assertEquals("2025-08-31", tool.endDate)
        assertEquals(15, tool.limit)
    }

    @Test
    fun `parseDecision handles lowercase keys`() {
        val raw = "tool: grep\nquery: AWS"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.Grep
        assertEquals("AWS", tool.query)
    }

    @Test
    fun `parseDecision returns ParseError when TOOL key missing`() {
        val raw = "QUERY: something"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun `parseDecision returns ParseError when timeline_search missing required keys`() {
        val raw = "TOOL: timeline_search\nSTART: 2025-06-01"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.ParseError)
    }
}
