package com.minibrain.ui.vm

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.PREF_LAST_INDEXED_AT
import com.minibrain.PREF_LAST_INDEXED_TREE
import com.minibrain.data.db.entities.ChatSessionSummary
import com.minibrain.data.repo.IndexingState
import com.minibrain.dataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val PREF_TREE_URI = stringPreferencesKey("tree_uri")
private const val RECENT_SESSION_COUNT = 3

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    val savedTreeUri: StateFlow<String?> = app.dataStore.data
        .map { prefs -> prefs[PREF_TREE_URI] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val indexingState: StateFlow<IndexingState> = app.container.documentRepository.indexingState

    val docCount: StateFlow<Int> = savedTreeUri
        .flatMapLatest { uri ->
            if (uri != null) app.container.documentRepository.observeDocCount(uri) else flowOf(0)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 今のフォルダを最後にインデックスした日時。フォルダを変えた直後（別フォルダの記録）なら null。 */
    val lastIndexedAt: StateFlow<Long?> = app.dataStore.data
        .map { prefs ->
            prefs[PREF_LAST_INDEXED_AT]?.takeIf { prefs[PREF_LAST_INDEXED_TREE] == prefs[PREF_TREE_URI] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Home から続きを開けるよう、やり取りのある最近のチャットを数件。 */
    val recentSessions: StateFlow<List<ChatSessionSummary>> = app.container.chatRepository
        .observeSessions()
        .map { sessions -> sessions.filter { it.messageCount > 0 }.take(RECENT_SESSION_COUNT) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            app.switchKnowledgeFolder(uri, oldUri = savedTreeUri.value)
        }
    }
}
