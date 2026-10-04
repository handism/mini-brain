@file:Suppress("unused", "UnusedImport")
package com.minibrain.ui.screens

import androidx.compose.ui.res.stringResource
import com.minibrain.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.ai.llm.DownloadProgress
import com.minibrain.ui.vm.OnboardingUiState
import com.minibrain.ui.vm.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onReady: (openChat: Boolean) -> Unit,
    vm: OnboardingViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is OnboardingUiState.Ready || state is OnboardingUiState.AlreadyReady) {
            onReady(vm.hasKnowledgeBase())
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))

            when (val s = state) {
                is OnboardingUiState.Checking -> CircularProgressIndicator()

                is OnboardingUiState.Required -> RequiredStateView(onStartDownload = { vm.startDownload() })

                is OnboardingUiState.Downloading -> DownloadingStateView(s)

                is OnboardingUiState.Initializing -> InitializingStateView()

                is OnboardingUiState.AlreadyReady, is OnboardingUiState.Ready -> {
                    CircularProgressIndicator()
                }

                is OnboardingUiState.Failure -> FailureStateView(
                    message = s.message,
                    detail = s.detail,
                    canTryCpu = s.canTryCpu,
                    onRetryCpu = { vm.retryWithCpu() },
                    onRetry = { vm.startDownload() }
                )
            }
        }
    }
}

@Composable
private fun RequiredStateView(onStartDownload: () -> Unit) {
    Text(
        stringResource(R.string.onboarding_download_notice),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onStartDownload, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_download_start))
    }
}

@Composable
private fun DownloadingStateView(state: OnboardingUiState.Downloading) {
    Text(stringResource(R.string.onboarding_downloading), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Text(state.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    if (state.embedder != null || state.llm != null) {
        state.embedder?.let {
            ModelProgress(label = stringResource(R.string.onboarding_embedder_model), progress = it)
            Spacer(Modifier.height(12.dp))
        }
        state.llm?.let {
            ModelProgress(label = stringResource(R.string.onboarding_llm_model), progress = it)
        }
    } else {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun ModelProgress(label: String, progress: DownloadProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                downloadProgressLabel(progress),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun downloadProgressLabel(progress: DownloadProgress): String = stringResource(
    R.string.onboarding_progress,
    (progress.fraction * 100).toInt(),
    (progress.bytesDownloaded / BYTES_PER_MB).toInt(),
    (progress.totalBytes / BYTES_PER_MB).toInt(),
)

private const val BYTES_PER_MB = 1024L * 1024L

@Composable
private fun InitializingStateView() {
    CircularProgressIndicator()
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.onboarding_initializing), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FailureStateView(
    message: String,
    detail: String?,
    canTryCpu: Boolean,
    onRetryCpu: () -> Unit,
    onRetry: () -> Unit,
) {
    var showDetail by remember { mutableStateOf(false) }

    Icon(
        Icons.Default.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.onboarding_error_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // 例外メッセージは利用者には読めないことが多いので、開いたときだけ出す
    if (!detail.isNullOrBlank()) {
        TextButton(onClick = { showDetail = !showDetail }) {
            Text(
                stringResource(
                    if (showDetail) R.string.onboarding_error_hide_detail else R.string.onboarding_error_show_detail
                )
            )
        }
        if (showDetail) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    if (canTryCpu) {
        Button(onClick = onRetryCpu, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_retry_cpu))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_retry))
        }
    } else {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_retry))
        }
    }
}
