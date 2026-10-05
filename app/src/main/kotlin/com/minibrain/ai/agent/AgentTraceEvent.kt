package com.minibrain.ai.agent

sealed interface AgentTraceEvent

data class PlannerDecisionEvent(
    val step: Int,
    val decision: String,
) : AgentTraceEvent

data class ToolCallEvent(
    val step: Int,
    val toolName: String,
    val arguments: String,
) : AgentTraceEvent

data class ObservationEvent(
    val step: Int,
    val summary: String,
) : AgentTraceEvent

data class FinalAnswerEvent(
    val answerLength: Int,
) : AgentTraceEvent

// Search First パイプライン用トレースイベント
data class QueryExpansionEvent(val queries: List<String>) : AgentTraceEvent
data class HyDeGeneratedEvent(val hypothetical: String) : AgentTraceEvent
data class MetadataSearchHitEvent(val hitCount: Int) : AgentTraceEvent
data class BM25SearchHitEvent(val query: String, val hitCount: Int) : AgentTraceEvent
data class GrepSearchHitEvent(val query: String, val hitCount: Int) : AgentTraceEvent
// 類似度の幅は VECTOR_MIN_SCORE を決めるための目安（ADR-040）。ヒットが無ければ null
data class VectorSearchHitEvent(
    val query: String,
    val hitCount: Int,
    val minScore: Float? = null,
    val maxScore: Float? = null,
) : AgentTraceEvent
data class CandidateMergeEvent(val totalCount: Int) : AgentTraceEvent
data class RerankEvent(val before: Int, val after: Int) : AgentTraceEvent
data class CoverageCheckEvent(val canAnswer: Boolean, val missingInformation: List<String>) : AgentTraceEvent
data class ExplorerStrategyEvent(val strategy: String, val reason: String) : AgentTraceEvent
