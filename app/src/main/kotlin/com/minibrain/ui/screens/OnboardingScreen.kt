@file:Suppress("unused", "UnusedImport")
package com.minibrain.ui.screens

import androidx.compose.ui.res.stringResource
import com.minibrain.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.ui.vm.OnboardingUiState
import com.minibrain.ui.vm.OnboardingViewModel

@Composable
fun OnboardingScreen(
    onReady: () -> Unit,
    vm: OnboardingViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is OnboardingUiState.Ready || state is OnboardingUiState.AlreadyReady) {
            onReady()
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
    Spacer(Modifier.height(12.dp))
    if (state.embedderFraction > 0f || state.llmFraction > 0f) {
        if (state.embedderFraction > 0f) {
            Text(stringResource(R.string.onboarding_embedder_model), style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(
                progress = { state.embedderFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        if (state.llmFraction > 0f) {
            Text(stringResource(R.string.onboarding_llm_model), style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(
                progress = { state.llmFraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun InitializingStateView() {
    CircularProgressIndicator()
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.onboarding_initializing), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun FailureStateView(
    message: String,
    canTryCpu: Boolean,
    onRetryCpu: () -> Unit,
    onRetry: () -> Unit,
) {
    Text(
        stringResource(R.string.onboarding_error, message),
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    if (canTryCpu) {
        Button(onClick = onRetryCpu, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_retry_cpu))
        }
        Spacer(Modifier.height(8.dp))
    }
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_retry))
    }
}
