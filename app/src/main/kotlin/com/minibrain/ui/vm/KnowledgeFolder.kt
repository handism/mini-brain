package com.minibrain.ui.vm

import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.minibrain.MiniBrainApp
import com.minibrain.dataStore

private val PREF_TREE_URI = stringPreferencesKey("tree_uri")

/**
 * 知識ベースのフォルダを切り替えて索引し直す。Home と設定のどちらから変えても同じ結果になるよう、
 * 前のフォルダのインデックスはここで消す（同じフォルダを選び直したときは消さない）。
 */
internal suspend fun MiniBrainApp.switchKnowledgeFolder(newUri: Uri, oldUri: String?) {
    val repository = container.documentRepository
    if (oldUri != null && oldUri != newUri.toString()) {
        repository.clearFolder(oldUri)
    }
    runCatching {
        contentResolver.takePersistableUriPermission(newUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    dataStore.edit { prefs -> prefs[PREF_TREE_URI] = newUri.toString() }
    repository.indexFolder(newUri)
}
