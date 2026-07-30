@file:Suppress("unused", "UnusedImport")
package com.minibrain.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.data.repo.IndexingState
import com.minibrain.ui.vm.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val treeUri by vm.savedTreeUri.collectAsStateWithLifecycle()
    val indexState by vm.indexingState.collectAsStateWithLifecycle()
    val docCount by vm.docCount.collectAsStateWithLifecycle()
    val chunkCount by vm.chunkCount.collectAsStateWithLifecycle()

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { vm.onFolderSelected(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mini Brain") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (treeUri == null) {
                // フォルダ未選択
                Spacer(Modifier.height(60.dp))
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                Text("mdフォルダを選択してください", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Markdownファイルが格納されたフォルダを選ぶと\n自動でインデックスを作成します",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = { folderLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Text("フォルダを選択", modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                // フォルダ選択済み
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("知識ベース", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            (Uri.parse(treeUri).lastPathSegment ?: treeUri.orEmpty())
                                .removePrefix("primary:"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            StatItem(label = "ファイル数", value = "$docCount")
                            StatItem(label = "チャンク数", value = "$chunkCount")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // インデックス状態
                when (val s = indexState) {
                    is IndexingState.Idle -> {}
                    is IndexingState.Progress -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                " インデックス中... ${s.current}/${s.total} ${s.fileName}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        if (s.total > 0) {
                            LinearProgressIndicator(
                                progress = { s.current.toFloat() / s.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    is IndexingState.Done -> {
                        Text(
                            "インデックス完了: ${s.fileCount}ファイル / ${s.chunkCount}チャンク",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is IndexingState.Error -> {
                        Text("エラー: ${s.message}", color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = onOpenChat,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = indexState !is IndexingState.Progress,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    Text("チャットを開く", modifier = Modifier.padding(start = 8.dp))
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = { vm.reindex() },
                        modifier = Modifier.weight(1f),
                        enabled = indexState !is IndexingState.Progress,
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("再インデックス", modifier = Modifier.padding(start = 4.dp))
                    }
                    OutlinedButton(
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("フォルダ変更", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
