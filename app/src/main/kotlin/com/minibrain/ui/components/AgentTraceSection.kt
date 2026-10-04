package com.minibrain.ui.components

import androidx.compose.ui.res.stringResource
import com.minibrain.R
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.minibrain.ai.agent.AgentTraceEvent
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
fun AgentTraceSection(events: List<AgentTraceEvent>) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            events.forEach { event ->
                AgentTraceEventItem(event = event)
            }
        }
    }
}

@Composable
private fun AgentTraceEventItem(event: AgentTraceEvent) {
    when (event) {
        is ToolCallEvent -> ToolCallItem(event)
        is ObservationEvent -> ObservationItem(event)
        is PlannerDecisionEvent -> PlannerDecisionItem(event)
        is FinalAnswerEvent -> FinalAnswerItem(event)
        is QueryExpansionEvent -> QueryExpansionItem(event)
        is HyDeGeneratedEvent -> HyDeGeneratedItem(event)
        is BM25SearchHitEvent -> SearchHitItem(stringResource(R.string.trace_source_bm25), event.hitCount)
        is MetadataSearchHitEvent -> SearchHitItem(stringResource(R.string.trace_source_metadata), event.hitCount)
        is GrepSearchHitEvent -> SearchHitItem(stringResource(R.string.trace_source_grep), event.hitCount)
        is VectorSearchHitEvent -> SearchHitItem(stringResource(R.string.trace_source_vector), event.hitCount)
        is CandidateMergeEvent -> CandidateMergeItem(event)
        is RerankEvent -> RerankItem(event)
        is CoverageCheckEvent -> CoverageCheckItem(event)
        is ExplorerStrategyEvent -> ExplorerStrategyItem(event)
    }
}

@Composable
private fun ToolCallItem(event: ToolCallEvent) {
    Text(
        stringResource(R.string.trace_tool_call),
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

@Composable
private fun ObservationItem(event: ObservationEvent) {
    Text(
        stringResource(R.string.trace_observation),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
    Text(
        event.summary,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlannerDecisionItem(event: PlannerDecisionEvent) {
    if (event.decision.startsWith("finalize")) {
        Text(
            stringResource(R.string.trace_done),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun FinalAnswerItem(event: FinalAnswerEvent) {
    Text(
        stringResource(R.string.trace_answer_length, event.answerLength),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun QueryExpansionItem(event: QueryExpansionEvent) {
    Text(
        stringResource(R.string.trace_query_expansion),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        event.queries.joinToString(" / "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HyDeGeneratedItem(event: HyDeGeneratedEvent) {
    Text(
        stringResource(R.string.trace_hyde),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        event.hypothetical,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchHitItem(source: String, hitCount: Int) {
    Text(
        stringResource(R.string.trace_search_hits, source, hitCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CandidateMergeItem(event: CandidateMergeEvent) {
    Text(
        stringResource(R.string.trace_merge, event.totalCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RerankItem(event: RerankEvent) {
    Text(
        stringResource(R.string.trace_rerank, event.before, event.after),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun CoverageCheckItem(event: CoverageCheckEvent) {
    val label = if (event.canAnswer) stringResource(R.string.trace_coverage_ok)
    else stringResource(R.string.trace_coverage_missing, event.missingInformation.joinToString(", "))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (event.canAnswer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ExplorerStrategyItem(event: ExplorerStrategyEvent) {
    Text(
        stringResource(R.string.trace_explorer, event.strategy),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
