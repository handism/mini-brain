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
