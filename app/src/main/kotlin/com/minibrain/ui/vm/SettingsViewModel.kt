package com.minibrain.ui.vm

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
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
private val PREF_SHOW_SEARCH_LOG = booleanPreferencesKey("show_search_log")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    val savedTreeUri = app.dataStore.data
        .map { prefs -> prefs[PREF_TREE_URI_SETTINGS] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showSearchLog = app.dataStore.data
        .map { prefs -> prefs[PREF_SHOW_SEARCH_LOG] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowSearchLog(enabled: Boolean) {
        viewModelScope.launch {
            app.dataStore.edit { prefs -> prefs[PREF_SHOW_SEARCH_LOG] = enabled }
        }
    }

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
