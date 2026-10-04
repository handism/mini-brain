package com.minibrain.ui.vm

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.data.repo.IndexingState
import com.minibrain.dataStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val PREF_TREE_URI_SETTINGS = stringPreferencesKey("tree_uri")
private val PREF_SHOW_SEARCH_LOG = booleanPreferencesKey("show_search_log")

/** 検索ログは開発者向けなので、普段使いでは出さない。 */
internal const val SHOW_SEARCH_LOG_DEFAULT = false

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    val savedTreeUri = app.dataStore.data
        .map { prefs -> prefs[PREF_TREE_URI_SETTINGS] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val showSearchLog = app.dataStore.data
        .map { prefs -> prefs[PREF_SHOW_SEARCH_LOG] ?: SHOW_SEARCH_LOG_DEFAULT }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SHOW_SEARCH_LOG_DEFAULT)

    fun setShowSearchLog(enabled: Boolean) {
        viewModelScope.launch {
            app.dataStore.edit { prefs -> prefs[PREF_SHOW_SEARCH_LOG] = enabled }
        }
    }

    val llmModelFile get() = app.container.modelDownloader.llmModelFile
    val embedderModelFile get() = app.container.modelDownloader.embedderModelFile

    /** 再インデックスを押したあと、設定画面でも進み具合を出すため。 */
    val indexingState: StateFlow<IndexingState> = app.container.documentRepository.indexingState

    fun reindex() {
        val uri = savedTreeUri.value ?: return
        // 走っている最中に重ねて押されても、終わったあとにもう一周しないようにする
        if (indexingState.value is IndexingState.Progress) return
        viewModelScope.launch {
            app.container.documentRepository.indexFolder(Uri.parse(uri))
        }
    }

    fun changeFolder(newUri: Uri) {
        viewModelScope.launch {
            app.switchKnowledgeFolder(newUri, oldUri = savedTreeUri.value)
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            app.container.chatRepository.clearAll()
        }
    }
}
