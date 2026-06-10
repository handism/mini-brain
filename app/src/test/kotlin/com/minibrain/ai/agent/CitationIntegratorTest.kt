package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import com.minibrain.ai.rag.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationIntegratorTest {

    private fun makeResult(vararg citations: Citation): ToolResult {
        val call = ToolCall(1, AgentTool.RrfSearch("q"))
        return ToolResult(call, "summary", citations.toList())
    }

    @Test
    fun `deduplicates by docId and headingPath keeping max score`() {
        val c1 = Citation("## 見出し", "snippet1", score = 0.5f, docId = 1, source = SourceType.GREP)
        val c2 = Citation("## 見出し", "snippet2", score = 0.8f, docId = 1, source = SourceType.GREP)
        val results = listOf(makeResult(c1), makeResult(c2))
        val integrated = CitationIntegrator.integrate(results)
        assertEquals(1, integrated.size)
        assertEquals(0.8f, integrated[0].score)
    }

    @Test
    fun `READ_FILE takes priority over GREP for same chunk`() {
        val grep = Citation("## h", "s", score = 0.9f, docId = 2, source = SourceType.GREP)
        val read = Citation("## h", "s", score = 0.5f, docId = 2, source = SourceType.READ_FILE)
        val results = listOf(makeResult(grep), makeResult(read))
        val integrated = CitationIntegrator.integrate(results)
        assertEquals(1, integrated.size)
        assertEquals(SourceType.READ_FILE, integrated[0].source)
    }

    @Test
    fun `budget limits total characters`() {
        val longSnippet = "a".repeat(1000)
        val citations = (1..20).map { i ->
            Citation("h$i", longSnippet, score = 1f, docId = i.toLong(), source = SourceType.READ_FILE)
        }
        val results = listOf(makeResult(*citations.toTypedArray()))
        val integrated = CitationIntegrator.integrate(results)
        assertTrue(integrated.size < 20)
        val totalChars = integrated.sumOf { it.headingPath.length + it.snippet.length + 6 }
        assertTrue(totalChars <= 4000 + 1006)
    }

    @Test
    fun `source priority order is READ_FILE GREP VECTOR RRF GLOB`() {
        val read = Citation("h", "s", docId = 1, source = SourceType.READ_FILE)
        val glob = Citation("h2", "s2", docId = 2, source = SourceType.GLOB)
        val grep = Citation("h3", "s3", docId = 3, source = SourceType.GREP)
        val results = listOf(makeResult(glob, grep, read))
        val integrated = CitationIntegrator.integrate(results)
        assertEquals(SourceType.READ_FILE, integrated[0].source)
        assertEquals(SourceType.GREP, integrated[1].source)
        assertEquals(SourceType.GLOB, integrated[2].source)
    }

    @Test
    fun `empty results returns empty list`() {
        val integrated = CitationIntegrator.integrate(emptyList())
        assertTrue(integrated.isEmpty())
    }
}
