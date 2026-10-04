package com.minibrain.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.R
import com.minibrain.data.repo.IndexingState
import com.minibrain.eval.EvalReport
import com.minibrain.eval.EvalResult
import com.minibrain.eval.PerCaseResult
import com.minibrain.ui.components.MessageCopyButton
import com.minibrain.ui.vm.EvalUiState
import com.minibrain.ui.vm.EvalViewModel

/**
 * 検索精度の評価（開発者向け）。評価セットの JSON を選んで SearchPipeline に流し、
 * Recall / MRR と取りこぼしの理由を出す。結果は Markdown でコピーして施策の前後を比べる。
 * 画面を離れると評価は止まる。
 */
@Composable
fun EvalScreen(
    onBack: () -> Unit,
    vm: EvalViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val fileName by vm.evalFileName.collectAsStateWithLifecycle()
    val indexingState by vm.indexingState.collectAsStateWithLifecycle()

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { vm.selectFile(it) }
    }

    // 数分かかるので、実行中に画面が消えて止まったように見えないようにする
    val view = LocalView.current
    val running = state is EvalUiState.Running
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    EvalScreenContent(
        state = state,
        fileName = fileName,
        indexing = indexingState is IndexingState.Progress,
        onBack = onBack,
        // JSON は端末によって application/octet-stream 扱いになるので絞らない
        onChooseFile = { fileLauncher.launch(arrayOf("*/*")) },
        onRun = vm::run,
        onCancel = vm::cancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EvalScreenContent(
    state: EvalUiState,
    fileName: String?,
    indexing: Boolean,
    onBack: () -> Unit,
    onChooseFile: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.eval_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state is EvalUiState.Done) MessageCopyButton(state.report)
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            val running = state is EvalUiState.Running
            ListItem(
                leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.eval_file)) },
                supportingContent = { Text(fileName ?: stringResource(R.string.not_selected)) },
                trailingContent = {
                    TextButton(onClick = onChooseFile, enabled = !running) {
                        Text(stringResource(R.string.eval_choose_file))
                    }
                },
            )
            if (fileName == null) {
                Text(
                    stringResource(R.string.eval_format_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is EvalUiState.Running -> RunningBlock(state, onCancel)
                    else -> {
                        Button(
                            onClick = onRun,
                            enabled = fileName != null && !indexing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (state is EvalUiState.Done) R.string.eval_rerun else R.string.eval_run
                                )
                            )
                        }
                        if (indexing) {
                            Text(
                                stringResource(R.string.eval_wait_indexing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state is EvalUiState.Failed) {
                    Text(
                        stringResource(R.string.eval_failed) + ": " + state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (state is EvalUiState.Done) {
                ResultBlock(state.result, state.unknownPaths)
            }
        }
    }
}

@Composable
private fun RunningBlock(state: EvalUiState.Running, onCancel: () -> Unit) {
    LinearProgressIndicator(
        progress = { if (state.total == 0) 0f else state.current.toFloat() / state.total },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        stringResource(R.string.eval_running, state.current + 1, state.total),
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        state.query,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.eval_cancel))
    }
}

@Composable
private fun ResultBlock(result: EvalResult, unknownPaths: List<String>) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Recall@${result.k}", result.recallAtK, Modifier.weight(1f))
            MetricCard("MRR", result.mrr, Modifier.weight(1f))
            MetricCard(stringResource(R.string.eval_metric_candidate_recall), result.candidateRecall, Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.eval_metric_candidate_recall_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.eval_cases_duration, result.cases, EvalReport.formatDuration(result.totalDurationMs)) +
                " · P@${result.k} ${EvalReport.fmt(result.precisionAtK)}",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (unknownPaths.isNotEmpty()) {
            Text(
                stringResource(R.string.eval_unknown_paths),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            unknownPaths.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        val misses = result.perCase.filter { it.missedPaths.isNotEmpty() || it.error != null }
        HorizontalDivider()
        if (misses.isEmpty()) {
            Text(stringResource(R.string.eval_all_hit), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(R.string.eval_misses, misses.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            misses.forEach { MissItem(it) }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(EvalReport.fmt(value), style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun MissItem(case: PerCaseResult) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(case.query, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${case.id} · " + stringResource(
                R.string.eval_case_metrics,
                EvalReport.fmt(case.recallAtK),
                EvalReport.fmt(case.reciprocalRank),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        case.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        case.missedPaths.forEach { path ->
            val reason = stringResource(
                if (path in case.droppedByRerank) R.string.eval_reason_dropped else R.string.eval_reason_not_retrieved
            )
            Text("$path — $reason", style = MaterialTheme.typography.bodySmall)
        }
    }
}
