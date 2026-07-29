package com.minibrain.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.ui.vm.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val treeUri by vm.savedTreeUri.collectAsStateWithLifecycle()
    val showSearchLog by vm.showSearchLog.collectAsStateWithLifecycle()
    val showClearDialog = remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { vm.changeFolder(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            SectionTitle("知識ベース")

            SettingItem(
                label = "現在のフォルダ",
                value = treeUri?.let { Uri.parse(it).lastPathSegment } ?: "未選択",
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { vm.reindex() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("再インデックス（変更ファイルのみ）")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("フォルダを変更")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionTitle("チャット履歴")

            OutlinedButton(
                onClick = { showClearDialog.value = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("チャット履歴をすべて削除", color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionTitle("開発者")

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("検索ログを表示する", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "回答の下にエージェントの検索過程を表示します",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = showSearchLog,
                    onCheckedChange = { vm.setShowSearchLog(it) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionTitle("モデル情報")

            SettingItem(label = "LLM モデル", value = "Gemma 4 E2B (LiteRT-LM)")
            SettingItem(
                label = "LLM ファイル",
                value = if (vm.llmModelFile.exists()) "${vm.llmModelFile.length() / 1024 / 1024} MB" else "未ダウンロード",
            )
            SettingItem(label = "Embedder", value = "Universal Sentence Encoder Multilingual")
            SettingItem(
                label = "Embedder ファイル",
                value = if (vm.embedderModelFile.exists()) "${vm.embedderModelFile.length() / 1024 / 1024} MB" else "未ダウンロード",
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            SectionTitle("プライバシー")

            Text(
                "すべての推論はオンデバイスで行われます。\n質問・回答・mdの内容はクラウドに送信されません。\nネットワーク通信は初回モデルダウンロード時のみです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showClearDialog.value) {
        AlertDialog(
            onDismissRequest = { showClearDialog.value = false },
            title = { Text("チャット履歴を削除") },
            text = { Text("すべてのチャット履歴が削除されます。この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearChatHistory()
                    showClearDialog.value = false
                    scope.launch { snackbarHostState.showSnackbar("チャット履歴を削除しました") }
                }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog.value = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
