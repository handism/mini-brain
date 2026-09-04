package com.minibrain.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTypesTest {

    @Test
    fun testGlobTool() {
        val tool = AgentTool.Glob("*.kt")
        assertEquals("glob", tool.traceName)
        assertEquals("*.kt", tool.traceArgs)
        assertEquals("ファイルパターン検索中...", tool.progressLabel)
        assertEquals("5 files matched", tool.observationKind(5, 100))
    }

    @Test
    fun testListDirTool() {
        val tool = AgentTool.ListDir("/src")
        assertEquals("list_dir", tool.traceName)
        assertEquals("/src", tool.traceArgs)
        assertEquals("フォルダ一覧取得中...", tool.progressLabel)
        assertEquals("3 entries listed", tool.observationKind(3, 100))
    }

    @Test
    fun testReadFileToolWithDocId() {
        val tool = AgentTool.ReadFile(10L, null)
        assertEquals("read_file", tool.traceName)
        assertEquals("docId=10", tool.traceArgs)
        assertEquals("ファイル読込中...", tool.progressLabel)
        assertEquals("200 chars loaded", tool.observationKind(1, 200))
    }

    @Test
    fun testReadFileToolWithPath() {
        val tool = AgentTool.ReadFile(null, "/src/test.kt")
        assertEquals("read_file", tool.traceName)
        assertEquals("/src/test.kt", tool.traceArgs)
        assertEquals("ファイル読込中...", tool.progressLabel)
        assertEquals("200 chars loaded", tool.observationKind(1, 200))
    }

    @Test
    fun testReadFileToolWithNulls() {
        val tool = AgentTool.ReadFile(null, null)
        assertEquals("read_file", tool.traceName)
        assertEquals("", tool.traceArgs)
        assertEquals("ファイル読込中...", tool.progressLabel)
        assertEquals("200 chars loaded", tool.observationKind(1, 200))
    }

    @Test
    fun testGrepToolWithScope() {
        val tool = AgentTool.Grep("query", "/src")
        assertEquals("grep", tool.traceName)
        assertEquals("\"query\",\nscope=/src", tool.traceArgs)
        assertEquals("キーワード検索中...", tool.progressLabel)
        assertEquals("10 hits returned", tool.observationKind(10, 100))
    }

    @Test
    fun testGrepToolWithoutScope() {
        val tool = AgentTool.Grep("query", null)
        assertEquals("grep", tool.traceName)
        assertEquals("\"query\"", tool.traceArgs)
        assertEquals("キーワード検索中...", tool.progressLabel)
        assertEquals("10 hits returned", tool.observationKind(10, 100))
    }

    @Test
    fun testVectorSearchTool() {
        val tool = AgentTool.VectorSearch("query", null, 5)
        assertEquals("vector_search", tool.traceName)
        assertEquals("\"query\",\nk=5", tool.traceArgs)
        assertEquals("ベクトル検索中...", tool.progressLabel)
        assertEquals("10 results returned", tool.observationKind(10, 100))
    }

    @Test
    fun testRrfSearchTool() {
        val tool = AgentTool.RrfSearch("query", 5)
        assertEquals("rrf_search", tool.traceName)
        assertEquals("\"query\",\nk=5", tool.traceArgs)
        assertEquals("ハイブリッド検索中...", tool.progressLabel)
        assertEquals("10 citations returned", tool.observationKind(10, 100))
    }

    @Test
    fun testTimelineSearchTool() {
        val tool = AgentTool.TimelineSearch("2023-01-01", "2023-12-31", 10)
        assertEquals("timeline_search", tool.traceName)
        assertEquals("2023-01-01,\n2023-12-31", tool.traceArgs)
        assertEquals("タイムライン検索中...", tool.progressLabel)
        assertEquals("15 documents found", tool.observationKind(15, 100))
    }
}