package com.minibrain.debug

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * intent.data のフォルダを初期表示にしてフォルダ選択を開き、選ばれたフォルダの読み取り権限を永続化する。
 * エミュレータ評価の androidTest が UiAutomator で「このフォルダを使用」「許可」を押す（ADR-045）。
 */
class GrantFolderActivity : ComponentActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) picker.launch(intent.data)
    }
}
