
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.R
import com.minibrain.data.repo.IndexingState
import com.minibrain.ui.components.folderDisplayName
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
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
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
            val uri = treeUri
            if (uri == null) {
                FolderUnselectedContent(
                    onSelectFolder = { folderLauncher.launch(null) }
                )
            } else {
                FolderSelectedContent(
                    treeUri = uri,
                    docCount = docCount,
                    chunkCount = chunkCount,
                    indexState = indexState,
                    onOpenChat = onOpenChat,
                    onReindex = { vm.reindex() },
                    onChangeFolder = { folderLauncher.launch(null) }
                )
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

@Composable
private fun FolderUnselectedContent(
    onSelectFolder: () -> Unit
) {
    // フォルダ未選択
    Spacer(Modifier.height(60.dp))
    Icon(
        Icons.Default.FolderOpen,
        contentDescription = null,
        modifier = Modifier.size(72.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.home_select_folder_title), style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.home_select_folder_desc),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onSelectFolder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.FolderOpen, contentDescription = null)
        Text(stringResource(R.string.home_select_folder), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun FolderSelectedContent(
    treeUri: String,
    docCount: Int,
    chunkCount: Int,
    indexState: IndexingState,
    onOpenChat: () -> Unit,
    onReindex: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    // フォルダ選択済み
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.home_knowledge_base), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                folderDisplayName(treeUri),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StatItem(label = stringResource(R.string.home_stat_files), value = "$docCount")
                StatItem(label = stringResource(R.string.home_stat_chunks), value = "$chunkCount")
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // インデックス状態
    when (val s = indexState) {
        is IndexingState.Idle -> { /* No UI to display when idle */ }
        is IndexingState.Progress -> IndexingProgress(s)
        is IndexingState.Done -> {
            Text(
                stringResource(R.string.home_indexing_done, s.fileCount, s.chunkCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        is IndexingState.Error -> {
            Text(stringResource(R.string.home_indexing_error, s.message), color = MaterialTheme.colorScheme.error)
        }
    }

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = onOpenChat,
        modifier = Modifier.fillMaxWidth(),
        enabled = indexState !is IndexingState.Progress,
    ) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
        Text(stringResource(R.string.home_open_chat), modifier = Modifier.padding(start = 8.dp))
    }

    if (indexState is IndexingState.Progress) {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.home_indexing_chat_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(
            onClick = onReindex,
            modifier = Modifier.weight(1f),
            enabled = indexState !is IndexingState.Progress,
        ) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.reindex), modifier = Modifier.padding(start = 4.dp))
        }
        OutlinedButton(
            onClick = onChangeFolder,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.home_change_folder), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun IndexingProgress(state: IndexingState.Progress) {
    // スキャンと解析（埋め込み）で total が変わるので、フェーズごとに計測をやり直す
    val phaseStartMs = remember(state.total) { System.currentTimeMillis() }
    val phaseStartCount = remember(state.total) { state.current }
    val remainingMs = estimateRemainingMs(
        done = state.current - phaseStartCount,
        remaining = state.total - state.current,
        elapsedMs = System.currentTimeMillis() - phaseStartMs,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.home_indexing, state.current, state.total, state.fileName),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
    if (state.total > 0) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.current.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    remainingMs?.let { ms ->
        Spacer(Modifier.height(4.dp))
        val minutes = (ms / 60_000L).toInt()
        Text(
            if (minutes >= 1) stringResource(R.string.home_indexing_eta_minutes, minutes + 1)
            else stringResource(R.string.home_indexing_eta_soon),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// 数件処理するまでは速度がぶれるので出さない
private const val ETA_MIN_SAMPLES = 3

/** 残り時間の推定。サンプルが少ないうちは null。 */
internal fun estimateRemainingMs(done: Int, remaining: Int, elapsedMs: Long): Long? {
    if (done < ETA_MIN_SAMPLES || remaining <= 0 || elapsedMs <= 0) return null
    return elapsedMs * remaining / done
}
