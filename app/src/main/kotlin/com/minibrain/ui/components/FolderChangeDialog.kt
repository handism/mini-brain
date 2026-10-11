package com.minibrain.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.minibrain.R

class FolderLauncher(
    private val requestConfirmation: () -> Unit,
    private val launchDirectly: () -> Unit,
) {
    fun launch(requireConfirmation: Boolean) {
        if (requireConfirmation) {
            requestConfirmation()
        } else {
            launchDirectly()
        }
    }
}

@Composable
fun rememberFolderLauncher(onFolderSelected: (Uri) -> Unit): FolderLauncher {
    var showDialog by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { onFolderSelected(it) }
    }

    if (showDialog) {
        FolderChangeDialog(
            onConfirm = {
                showDialog = false
                launcher.launch(null)
            },
            onDismiss = { showDialog = false },
        )
    }

    return remember(launcher) {
        FolderLauncher(
            requestConfirmation = { showDialog = true },
            launchDirectly = { launcher.launch(null) }
        )
    }
}

/** フォルダを変えると今のインデックスが消えるので、フォルダ選択を開く前に確かめる（Home・設定共通）。 */
@Composable
fun FolderChangeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_change_dialog_title)) },
        text = { Text(stringResource(R.string.folder_change_dialog_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.folder_change_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
