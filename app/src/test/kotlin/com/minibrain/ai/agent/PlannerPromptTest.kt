package com.minibrain.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerPromptTest {

    @Test
    fun `parseDecision returns Glob call`() {
        val raw = """{"tool":"glob","args":{"pattern":"2026/06/*"}}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.Glob)
        assertEquals("2026/06/*", (tool as AgentTool.Glob).pattern)
    }

    @Test
    fun `parseDecision returns ReadFile with docId`() {
        val raw = """{"tool":"read_file","args":{"docId":12}}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.ReadFile)
        assertEquals(12L, (tool as AgentTool.ReadFile).docId)
    }

    @Test
    fun `parseDecision returns ReadFile with path`() {
        val raw = """{"tool":"read_file","args":{"path":"proj/設計メモ.md"}}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.ReadFile
        assertEquals("proj/設計メモ.md", tool.path)
    }

    @Test
    fun `parseDecision returns Finalize`() {
        val raw = """{"action":"finalize","reason":"enough info"}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Finalize)
        assertEquals("enough info", (decision as PlannerDecision.Finalize).reason)
    }

    @Test
    fun `parseDecision returns ParseError for unknown tool`() {
        val raw = """{"tool":"unknown_tool","args":{}}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun `parseDecision strips markdown code block`() {
        val raw = "```json\n{\"tool\":\"glob\",\"args\":{\"pattern\":\"proj/*\"}}\n```"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
    }

    @Test
    fun `parseDecision handles noise before JSON`() {
        val raw = "Sure! Here is my decision:\n{\"tool\":\"list_dir\",\"args\":{\"folder\":\"proj\"}}"
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool
        assertTrue(tool is AgentTool.ListDir)
    }

    @Test
    fun `parseDecision returns ParseError for empty string`() {
        val decision = PlannerPrompt.parseDecision("")
        assertTrue(decision is PlannerDecision.ParseError)
    }

    @Test
    fun `parseDecision returns Grep with scope`() {
        val raw = """{"tool":"grep","args":{"query":"コスト削減","scope":"proj/"}}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.Grep
        assertEquals("コスト削減", tool.query)
        assertEquals("proj/", tool.scope)
    }

    @Test
    fun `parseDecision returns VectorSearch`() {
        val raw = """{"tool":"vector_search","args":{"query":"機械学習","k":15}}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.VectorSearch
        assertEquals("機械学習", tool.query)
        assertEquals(15, tool.k)
    }

    @Test
    fun `parseDecision returns RrfSearch`() {
        val raw = """{"tool":"rrf_search","args":{"query":"読書記録"}}"""
        val decision = PlannerPrompt.parseDecision(raw)
        assertTrue(decision is PlannerDecision.Call)
        val tool = (decision as PlannerDecision.Call).tool as AgentTool.RrfSearch
        assertEquals("読書記録", tool.query)
        assertEquals(10, tool.k)
    }
}
