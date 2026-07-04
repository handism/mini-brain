package com.minibrain.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.minibrain.ai.agent.BM25SearchHitEvent
import com.minibrain.ai.agent.CandidateMergeEvent
import com.minibrain.ai.agent.CoverageCheckEvent
import com.minibrain.ai.agent.ExplorerStrategyEvent
import com.minibrain.ai.agent.FinalAnswerEvent
import com.minibrain.ai.agent.GrepSearchHitEvent
import com.minibrain.ai.agent.HyDeGeneratedEvent
import com.minibrain.ai.agent.MetadataSearchHitEvent
import com.minibrain.ai.agent.ObservationEvent
import com.minibrain.ai.agent.PlannerDecisionEvent
import com.minibrain.ai.agent.QueryExpansionEvent
import com.minibrain.ai.agent.RerankEvent
import com.minibrain.ai.agent.ToolCallEvent
import com.minibrain.ai.agent.VectorSearchHitEvent

@Composable
fun AgentTraceSection(events: List<com.minibrain.ai.agent.AgentTraceEvent>) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            events.forEach { event ->
                when (event) {
                    is ToolCallEvent -> {
                        Text(
                            "Planner",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${event.toolName}(\n${event.arguments}\n)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            softWrap = false,
                        )
                    }
                    is ObservationEvent -> {
                        Text(
                            "Observation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(
                            event.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is PlannerDecisionEvent -> {
                        if (event.decision.startsWith("finalize")) {
                            Text(
                                "完了",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    is FinalAnswerEvent -> {
                        Text(
                            "回答 ${event.answerLength}字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    is QueryExpansionEvent -> {
                        Text(
                            "Query Expansion",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            event.queries.joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is HyDeGeneratedEvent -> {
                        Text(
                            "HyDE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            event.hypothetical,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is BM25SearchHitEvent -> {
                        Text(
                            "BM25  ${event.hitCount} hits",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is MetadataSearchHitEvent -> {
                        Text(
                            "Metadata  ${event.hitCount} hits",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is GrepSearchHitEvent -> {
                        Text(
                            "Grep  ${event.hitCount} hits",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is VectorSearchHitEvent -> {
                        Text(
                            "Vector  ${event.hitCount} hits",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is CandidateMergeEvent -> {
                        Text(
                            "Merge  ${event.totalCount} candidates",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is RerankEvent -> {
                        Text(
                            "Rerank  ${event.before} → ${event.after}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    is CoverageCheckEvent -> {
                        val label = if (event.canAnswer) "Coverage  OK" else "Coverage  NG — missing: ${event.missingInformation.joinToString(", ")}"
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (event.canAnswer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                    is ExplorerStrategyEvent -> {
                        Text(
                            "Explorer  ${event.strategy}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
