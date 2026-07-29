package com.minibrain.ui.screens

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
    val state = vm.state.collectAsStateWithLifecycle().value

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
                text = "Mini Brain",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "プライベートRAG — すべてオンデバイスで動作",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))

            when (val s = state) {
                is OnboardingUiState.Checking -> CircularProgressIndicator()

                is OnboardingUiState.Required -> {
                    Text(
                        "初回起動時に約 2.8GB のモデルをダウンロードします。\nWi-Fi 接続を推奨します。",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { vm.startDownload() }, modifier = Modifier.fillMaxWidth()) {
                        Text("ダウンロード開始")
                    }
                }

                is OnboardingUiState.Downloading -> {
                    Text("モデルをダウンロード中...", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    if (s.embedderFraction > 0f || s.llmFraction > 0f) {
                        if (s.embedderFraction > 0f) {
                            Text("Embedderモデル", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(
                                progress = { s.embedderFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (s.llmFraction > 0f) {
                            Text("Gemma 4 E2B LLM (約2.5GB)", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(
                                progress = { s.llmFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }

                is OnboardingUiState.Initializing -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("モデルを初期化中...", style = MaterialTheme.typography.bodyMedium)
                }

                is OnboardingUiState.AlreadyReady, is OnboardingUiState.Ready -> {
                    CircularProgressIndicator()
                }

                is OnboardingUiState.Failure -> {
                    Text(
                        "エラーが発生しました:\n${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (s.canTryCpu) {
                        Button(onClick = { vm.retryWithCpu() }, modifier = Modifier.fillMaxWidth()) {
                            Text("CPUモードで試す (低速ですが安定します)")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(onClick = { vm.startDownload() }, modifier = Modifier.fillMaxWidth()) {
                        Text("再試行")
                    }
                }
            }
        }
    }
}
