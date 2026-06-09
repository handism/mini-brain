package com.minibrain.ui.vm

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.dataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val PREF_TREE_URI_SETTINGS = stringPreferencesKey("tree_uri")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    val savedTreeUri = app.dataStore.data
        .map { prefs -> prefs[PREF_TREE_URI_SETTINGS] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val llmModelFile get() = app.modelDownloader.llmModelFile
    val embedderModelFile get() = app.modelDownloader.embedderModelFile

    fun reindex() {
        val uri = savedTreeUri.value ?: return
        viewModelScope.launch {
            app.documentRepository.indexFolder(Uri.parse(uri))
        }
    }

    fun changeFolder(newUri: Uri) {
        viewModelScope.launch {
            // 既存フォルダのインデックスを削除して新規インデックス
            val oldUri = savedTreeUri.value
            if (oldUri != null) {
                app.documentRepository.clearFolder(oldUri)
            }
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    newUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            app.dataStore.edit { prefs -> prefs[PREF_TREE_URI_SETTINGS] = newUri.toString() }
            app.documentRepository.indexFolder(newUri)
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            app.chatRepository.clearAll()
        }
    }
}
