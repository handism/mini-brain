package com.minibrain.ai.agent

import com.minibrain.ai.rag.Citation
import kotlinx.coroutines.flow.Flow

sealed class AgentTool {
    data class Glob(val pattern: String) : AgentTool()
    data class ListDir(val folder: String) : AgentTool()
    data class ReadFile(val docId: Long?, val path: String?) : AgentTool()
    data class Grep(val query: String, val scope: String?) : AgentTool()
    data class VectorSearch(val query: String, val scope: String?, val k: Int = 10) : AgentTool()
    data class RrfSearch(val query: String, val k: Int = 10) : AgentTool()
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
)
