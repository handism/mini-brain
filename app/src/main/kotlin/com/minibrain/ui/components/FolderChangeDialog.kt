package com.minibrain.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.minibrain.R

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
