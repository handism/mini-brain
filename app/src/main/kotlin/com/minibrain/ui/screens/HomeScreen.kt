
package com.minibrain.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import com.minibrain.ui.components.FolderChangeDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.R
import com.minibrain.data.db.entities.ChatSessionSummary
import com.minibrain.data.repo.IndexingState
import com.minibrain.ui.components.folderDisplayName
import com.minibrain.ui.vm.HomeViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: (Long) -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    val treeUri by vm.savedTreeUri.collectAsStateWithLifecycle()
    val indexState by vm.indexingState.collectAsStateWithLifecycle()
    val docCount by vm.docCount.collectAsStateWithLifecycle()
    val lastIndexedAt by vm.lastIndexedAt.collectAsStateWithLifecycle()
    val recentSessions by vm.recentSessions.collectAsStateWithLifecycle()
    var showFolderChangeDialog by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                // タブレット・横画面でボタンが端まで伸びないよう、中央に寄せて幅を抑える
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = HOME_CONTENT_MAX_WIDTH),
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
                    stats = KnowledgeBaseStats(docCount, lastIndexedAt),
                    indexState = indexState,
                    onOpenChat = onOpenChat,
                    onChangeFolder = { showFolderChangeDialog = true }
                )
                if (recentSessions.isNotEmpty()) {
                    RecentChats(sessions = recentSessions, onOpenSession = onOpenSession)
                }
            }
        }
    }

    if (showFolderChangeDialog) {
        FolderChangeDialog(
            onConfirm = {
                showFolderChangeDialog = false
                folderLauncher.launch(null)
            },
            onDismiss = { showFolderChangeDialog = false },
        )
    }
}

private val HOME_CONTENT_MAX_WIDTH = 600.dp

// チャンク数は利用者向けの指標ではないので出さない（ADR-034）
private data class KnowledgeBaseStats(val docCount: Int, val lastIndexedAt: Long?)

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
    stats: KnowledgeBaseStats,
    indexState: IndexingState,
    onOpenChat: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    // フォルダ選択済み
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.home_knowledge_base), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                folderDisplayName(treeUri),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.home_note_count, stats.docCount), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.home_updated_at, stats.lastIndexedAt?.let { formatTimestamp(it) } ?: stringResource(R.string.home_stat_none)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onChangeFolder) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.home_change_folder), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // インデックス状態
    when (val s = indexState) {
        is IndexingState.Idle -> { /* No UI to display when idle */ }
        is IndexingState.Progress -> IndexingProgress(s)
        is IndexingState.Done -> IndexingDoneMessage(s)
        is IndexingState.Error -> IndexingErrorMessage(s.message)
    }

    Spacer(Modifier.height(24.dp))

    // インデックス中も開ける。進み具合はチャット側のバナーにも出る（起動直後と同じ扱い）
    Button(
        onClick = onOpenChat,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
        Text(stringResource(R.string.home_open_chat), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RecentChats(sessions: List<ChatSessionSummary>, onOpenSession: (Long) -> Unit) {
    Spacer(Modifier.height(32.dp))
    Text(
        stringResource(R.string.home_recent_chats),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
    // ListItem の背景をカードと同じ色にして、1 枚のカードに並んで見えるようにする
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        sessions.forEach { session ->
            SessionListItem(
                session = session,
                onClick = { onOpenSession(session.id) },
                colors = ListItemDefaults.colors(containerColor = containerColor),
            )
        }
    }
}

// 完了メッセージを出し続けると「まだ何か起きている」ように見えるので、少し出したら消す
private const val DONE_MESSAGE_VISIBLE_MS = 4_000L

@Composable
private fun IndexingDoneMessage(state: IndexingState.Done) {
    var visible by remember(state) { mutableStateOf(true) }
    LaunchedEffect(state) {
        delay(DONE_MESSAGE_VISIBLE_MS)
        visible = false
    }
    AnimatedVisibility(visible = visible) {
        Text(
            stringResource(R.string.home_indexing_done, state.fileCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun IndexingErrorMessage(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Text(
                stringResource(R.string.home_indexing_error, message),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
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
            overflow = TextOverflow.Ellipsis,
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
