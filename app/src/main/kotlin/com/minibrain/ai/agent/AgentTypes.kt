package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import kotlinx.coroutines.flow.Flow

sealed class AgentTool {
    /** Planner プロンプト / トレースで使う識別子。短く保つ。 */
    abstract val traceName: String

    /** トレースに表示する引数。複数引数があるツールはカンマ + 改行で連結する。 */
    abstract val traceArgs: String

    /** UI 進捗ラベル。日本語で簡潔に。 */
    abstract val progressLabel: String

    /** Observation サマリの最後に付く「○ ... returned」相当の動詞句。件数は呼び出し側で前置する。 */
    abstract fun observationKind(citationCount: Int, summaryChars: Int): String

    data class Glob(val pattern: String) : AgentTool() {
        override val traceName = "glob"
        override val traceArgs get() = pattern
        override val progressLabel = "ファイルパターン検索中..."
        override fun observationKind(citationCount: Int, summaryChars: Int) = "$citationCount files matched"
    }

    data class ListDir(val folder: String) : AgentTool() {
        override val traceName = "list_dir"
        override val traceArgs get() = folder
        override val progressLabel = "フォルダ一覧取得中..."
        override fun observationKind(citationCount: Int, summaryChars: Int) = "$citationCount entries listed"
    }

    data class ReadFile(val docId: Long?, val path: String?) : AgentTool() {
        override val traceName = "read_file"
        override val traceArgs get() = docId?.let { "docId=$it" } ?: path ?: ""
        override val progressLabel = "ファイル読込中..."
        override fun observationKind(citationCount: Int, summaryChars: Int) = "$summaryChars chars loaded"
    }

    data class Grep(val query: String, val scope: String?) : AgentTool() {
        override val traceName = "grep"
        override val traceArgs get() = "\"$query\"${scope?.let { ",\nscope=$it" } ?: ""}"
        override val progressLabel = "キーワード検索中..."
        override fun observationKind(citationCount: Int, summaryChars: Int) = "$citationCount hits returned"
    }

    data class VectorSearch(val query: String, val scope: String?, val k: Int = 10) : AgentTool() {
        override val traceName = "vector_search"
        override val traceArgs get() = "\"$query\",\nk=$k"
        override val progressLabel = "ベクトル検索中..."
        override fun observationKind(citationCount: Int, summaryChars: Int) = "$citationCount results returned"
    }

    data class RrfSearch(val query: String, val k: Int = 10) : AgentTool() {
        override val traceName = "rrf_search"
        override val traceArgs get() = "\"$query\",\nk=$k"
        override val progressLabel = "ハイブリッド検索中..."
        override fun observationKind(citationCount: Int, summaryChars: Int) = "$citationCount citations returned"
    }

    data class TimelineSearch(val startDate: String, val endDate: String, val limit: Int = 20) : AgentTool() {
        override val traceName = "timeline_search"
        override val traceArgs get() = "$startDate,\n$endDate"
        override val progressLabel = "タイムライン検索中..."
        override fun observationKind(citationCount: Int, summaryChars: Int) = "$citationCount documents found"
    }
}

data class ToolCall(val iteration: Int, val tool: AgentTool)

data class ToolResult(
    val call: ToolCall,
    val summary: String,
    val citations: List<Citation>,
)

data class Observation(
    val call: ToolCall,
    val text: String,
    val full: Boolean,
)

sealed class PlannerDecision {
    data class Call(val tool: AgentTool) : PlannerDecision()
    data class Finalize(val reason: String) : PlannerDecision()
    data object ParseError : PlannerDecision()
}

data class AgentResult(
    val citations: List<Citation>,
    val answerFlow: Flow<String>,
    val traceEvents: List<AgentTraceEvent> = emptyList(),
)
