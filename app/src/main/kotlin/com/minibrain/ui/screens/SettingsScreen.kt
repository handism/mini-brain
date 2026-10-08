@file:Suppress("unused", "UnusedImport")
package com.minibrain.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import com.minibrain.ai.llm.LlmModel
import com.minibrain.ui.vm.ModelSwitchState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.minibrain.data.repo.IndexingState
import com.minibrain.ui.components.FolderChangeDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.minibrain.R
import com.minibrain.ui.components.folderDisplayName
import com.minibrain.ui.vm.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenEval: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val treeUri by vm.savedTreeUri.collectAsStateWithLifecycle()
    val showSearchLog by vm.showSearchLog.collectAsStateWithLifecycle()
    val indexingState by vm.indexingState.collectAsStateWithLifecycle()
    val llmModel by vm.llmModel.collectAsStateWithLifecycle()
    val modelSwitch by vm.modelSwitch.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showFolderChangeDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clearedMessage = stringResource(R.string.settings_history_cleared)

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { vm.changeFolder(it) }
    }

    SettingsScreenContent(
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        treeUri = treeUri,
        showSearchLog = showSearchLog,
        indexingProgress = indexingState as? IndexingState.Progress,
        llmModel = llmModel,
        llmModelFile = vm.llmFile(llmModel),
        modelSwitch = modelSwitch,
        onChangeModel = {
            // 失敗したときは、行をタップするとそのまま同じモデルで再試行する
            when (val st = modelSwitch) {
                is ModelSwitchState.Failed -> vm.switchLlmModel(st.target)
                ModelSwitchState.Idle -> showModelDialog = true
                else -> Unit
            }
        },
        embedderModelFile = vm.embedderModelFile,
        onReindex = { vm.reindex() },
        // 初めて選ぶときは消えるインデックスが無いので、確認せずに開く
        onChangeFolder = {
            if (treeUri == null) folderLauncher.launch(null) else showFolderChangeDialog = true
        },
        onClearChat = { showClearDialog = true },
        onShowSearchLogChange = { vm.setShowSearchLog(it) },
        onOpenEval = onOpenEval,
    )

    if (showModelDialog) {
        LlmModelDialog(
            current = llmModel,
            onConfirm = { target ->
                showModelDialog = false
                vm.switchLlmModel(target)
            },
            onDismiss = { showModelDialog = false },
        )
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

    if (showClearDialog) {
        ClearChatDialog(
            onConfirm = {
                vm.clearChatHistory()
                showClearDialog = false
                scope.launch { snackbarHostState.showSnackbar(clearedMessage) }
            },
            onDismiss = { showClearDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenContent(
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    treeUri: String?,
    showSearchLog: Boolean,
    indexingProgress: IndexingState.Progress?,
    llmModel: LlmModel,
    llmModelFile: java.io.File,
    modelSwitch: ModelSwitchState,
    onChangeModel: () -> Unit,
    embedderModelFile: java.io.File,
    onReindex: () -> Unit,
    onChangeFolder: () -> Unit,
    onClearChat: () -> Unit,
    onShowSearchLogChange: (Boolean) -> Unit,
    onOpenEval: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            KnowledgeBaseSection(
                treeUri = treeUri,
                indexingProgress = indexingProgress,
                onReindex = onReindex,
                onChangeFolder = onChangeFolder
            )
            ChatHistorySection(onClearChat = onClearChat)
            ModelInfoSection(
                llmModel = llmModel,
                llmModelFile = llmModelFile,
                modelSwitch = modelSwitch,
                onChangeModel = onChangeModel,
                embedderModelFile = embedderModelFile
            )
            PrivacySection()
            // 普段は触らない項目なので最後に置く
            DeveloperSection(
                showSearchLog = showSearchLog,
                onShowSearchLogChange = onShowSearchLogChange,
                onOpenEval = onOpenEval,
            )
        }
    }
}

@Composable
private fun KnowledgeBaseSection(
    treeUri: String?,
    indexingProgress: IndexingState.Progress?,
    onReindex: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_section_knowledge_base))
    SettingsListItem(
        icon = Icons.Default.Folder,
        headline = stringResource(R.string.settings_current_folder),
        supporting = treeUri?.let { folderDisplayName(it) } ?: stringResource(R.string.not_selected),
        trailing = {
            Text(
                stringResource(R.string.change_folder),
                color = if (indexingProgress == null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // インデックス中に切り替えると、走っている索引の後で消すことになるので待ってもらう
        onClick = onChangeFolder.takeIf { indexingProgress == null },
    )
    SettingsListItem(
        icon = Icons.Default.Sync,
        headline = stringResource(R.string.settings_reindex_changed),
        supporting = indexingProgress?.let {
            stringResource(R.string.settings_reindexing, it.current, it.total)
        } ?: stringResource(R.string.settings_reindex_changed_desc),
        trailing = indexingProgress?.let {
            { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        },
        onClick = onReindex.takeIf { indexingProgress == null },
    )
}

@Composable
private fun ChatHistorySection(onClearChat: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_section_chat_history))
    SettingsListItem(
        icon = Icons.Default.DeleteForever,
        headline = stringResource(R.string.settings_clear_history),
        contentColor = MaterialTheme.colorScheme.error,
        onClick = onClearChat,
    )
}

@Composable
private fun DeveloperSection(
    showSearchLog: Boolean,
    onShowSearchLogChange: (Boolean) -> Unit,
    onOpenEval: () -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_section_developer))
    // 行全体をタップで切り替えられるようにし、Switch 自体はクリックを持たない
    ListItem(
        leadingContent = { Icon(Icons.AutoMirrored.Filled.ManageSearch, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_show_search_log)) },
        supportingContent = { Text(stringResource(R.string.settings_show_search_log_desc)) },
        trailingContent = { Switch(checked = showSearchLog, onCheckedChange = null) },
        modifier = Modifier.toggleable(
            value = showSearchLog,
            role = Role.Switch,
            onValueChange = onShowSearchLogChange,
        ),
    )
    SettingsListItem(
        icon = Icons.Default.Insights,
        headline = stringResource(R.string.settings_eval),
        supporting = stringResource(R.string.settings_eval_desc),
        onClick = onOpenEval,
    )
}

@Composable
private fun ModelInfoSection(
    llmModel: LlmModel,
    llmModelFile: java.io.File,
    modelSwitch: ModelSwitchState,
    onChangeModel: () -> Unit,
    embedderModelFile: java.io.File,
) {
    SectionTitle(stringResource(R.string.settings_section_model))
    val supporting = when (modelSwitch) {
        ModelSwitchState.Idle -> llmModelName(llmModel) + " · " + modelFileSize(llmModelFile)
        is ModelSwitchState.Downloading -> modelSwitch.fraction?.let {
            stringResource(R.string.settings_llm_downloading, llmModelName(modelSwitch.target), (it * 100).toInt())
        } ?: stringResource(R.string.settings_llm_downloading_unknown, llmModelName(modelSwitch.target))
        is ModelSwitchState.Loading -> stringResource(R.string.settings_llm_loading, llmModelName(modelSwitch.target))
        is ModelSwitchState.Failed -> stringResource(R.string.settings_llm_switch_failed, llmModelName(modelSwitch.target))
    }
    val busy = modelSwitch is ModelSwitchState.Downloading || modelSwitch is ModelSwitchState.Loading
    SettingsListItem(
        icon = Icons.Default.Psychology,
        headline = stringResource(R.string.settings_llm_model),
        supporting = supporting,
        contentColor = if (modelSwitch is ModelSwitchState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
        trailing = if (busy) {
            { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else {
            {
                Text(stringResource(R.string.change), color = MaterialTheme.colorScheme.primary)
            }
        },
        onClick = onChangeModel.takeIf { !busy },
    )
    SettingsListItem(
        icon = Icons.Default.Hub,
        headline = stringResource(R.string.settings_embedder),
        supporting = stringResource(R.string.settings_embedder_value) + " · " + modelFileSize(embedderModelFile),
    )
}

@Composable
private fun llmModelName(model: LlmModel): String = stringResource(
    when (model) {
        LlmModel.E2B -> R.string.llm_model_e2b
        LlmModel.E4B -> R.string.llm_model_e4b
    }
)

@Composable
private fun llmModelDescription(model: LlmModel): String = stringResource(
    when (model) {
        LlmModel.E2B -> R.string.llm_model_e2b_desc
        LlmModel.E4B -> R.string.llm_model_e4b_desc
    }
)

@Composable
private fun LlmModelDialog(
    current: LlmModel,
    onConfirm: (LlmModel) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_llm_model)) },
        text = {
            Column(Modifier.selectableGroup()) {
                LlmModel.entries.forEach { model ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = model == selected, role = Role.RadioButton, onClick = { selected = model })
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = model == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(llmModelName(model), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                llmModelDescription(model),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.settings_llm_dialog_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = selected != current) {
                Text(stringResource(R.string.settings_llm_switch))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun modelFileSize(file: java.io.File): String =
    if (file.exists()) stringResource(R.string.settings_file_size_mb, (file.length() / 1024 / 1024).toInt())
    else stringResource(R.string.settings_not_downloaded)

@Composable
private fun PrivacySection() {
    SectionTitle(stringResource(R.string.settings_section_privacy))
    SettingsListItem(
        icon = Icons.Default.Lock,
        headline = stringResource(R.string.settings_privacy_text),
    )
}

@Composable
private fun ClearChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_clear_dialog_title)) },
        text = { Text(stringResource(R.string.settings_clear_dialog_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SettingsListItem(
    icon: ImageVector,
    headline: String,
    supporting: String? = null,
    contentColor: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val color = contentColor.takeOrElse { MaterialTheme.colorScheme.onSurface }
    ListItem(
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = contentColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
            )
        },
        headlineContent = { Text(headline, color = color) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
