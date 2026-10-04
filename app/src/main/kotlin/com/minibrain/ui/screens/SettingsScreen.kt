@file:Suppress("unused", "UnusedImport")
package com.minibrain.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.minibrain.R
import com.minibrain.ui.components.folderDisplayName
import com.minibrain.ui.vm.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val treeUri by vm.savedTreeUri.collectAsStateWithLifecycle()
    val showSearchLog by vm.showSearchLog.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
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
        llmModelFile = vm.llmModelFile,
        embedderModelFile = vm.embedderModelFile,
        onReindex = { vm.reindex() },
        onChangeFolder = { folderLauncher.launch(null) },
        onClearChat = { showClearDialog = true },
        onShowSearchLogChange = { vm.setShowSearchLog(it) }
    )

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
    llmModelFile: java.io.File,
    embedderModelFile: java.io.File,
    onReindex: () -> Unit,
    onChangeFolder: () -> Unit,
    onClearChat: () -> Unit,
    onShowSearchLogChange: (Boolean) -> Unit,
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
                .padding(16.dp),
        ) {
            KnowledgeBaseSection(
                treeUri = treeUri,
                onReindex = onReindex,
                onChangeFolder = onChangeFolder
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            ChatHistorySection(
                onClearChat = onClearChat
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            DeveloperSection(
                showSearchLog = showSearchLog,
                onShowSearchLogChange = onShowSearchLogChange
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            ModelInfoSection(
                llmModelFile = llmModelFile,
                embedderModelFile = embedderModelFile
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            PrivacySection()
        }
    }
}

@Composable
private fun KnowledgeBaseSection(
    treeUri: String?,
    onReindex: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_section_knowledge_base))

    SettingItem(
        label = stringResource(R.string.settings_current_folder),
        value = treeUri?.let { folderDisplayName(it) } ?: stringResource(R.string.not_selected),
    )
    Spacer(Modifier.height(8.dp))
    FilledTonalButton(
        onClick = onReindex,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_reindex_changed))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onChangeFolder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.change_folder))
    }
}

@Composable
private fun ChatHistorySection(onClearChat: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_section_chat_history))

    OutlinedButton(
        onClick = onClearChat,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_clear_history), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DeveloperSection(
    showSearchLog: Boolean,
    onShowSearchLogChange: (Boolean) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_section_developer))

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_show_search_log), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.settings_show_search_log_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = showSearchLog,
            onCheckedChange = onShowSearchLogChange,
        )
    }
}

@Composable
private fun ModelInfoSection(
    llmModelFile: java.io.File,
    embedderModelFile: java.io.File,
) {
    SectionTitle(stringResource(R.string.settings_section_model))

    SettingItem(
        label = stringResource(R.string.settings_llm_model),
        value = stringResource(R.string.settings_llm_model_value),
    )
    SettingItem(
        label = stringResource(R.string.settings_llm_file),
        value = modelFileSize(llmModelFile),
    )
    SettingItem(
        label = stringResource(R.string.settings_embedder),
        value = stringResource(R.string.settings_embedder_value),
    )
    SettingItem(
        label = stringResource(R.string.settings_embedder_file),
        value = modelFileSize(embedderModelFile),
    )
}

@Composable
private fun modelFileSize(file: java.io.File): String =
    if (file.exists()) stringResource(R.string.settings_file_size_mb, (file.length() / 1024 / 1024).toInt())
    else stringResource(R.string.settings_not_downloaded)

@Composable
private fun PrivacySection() {
    SectionTitle(stringResource(R.string.settings_section_privacy))

    Text(
        stringResource(R.string.settings_privacy_text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SettingItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}
