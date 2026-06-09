package com.minibrain.ui.vm

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
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

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    val savedTreeUri: StateFlow<String?> = app.dataStore.data
        .map { prefs -> prefs[PREF_TREE_URI] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val indexingState: StateFlow<IndexingState> = app.documentRepository.indexingState

    val docCount: StateFlow<Int> = savedTreeUri
        .flatMapLatest { uri ->
            if (uri != null) app.documentRepository.observeDocCount(uri) else flowOf(0)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val chunkCount: StateFlow<Int> = savedTreeUri
        .flatMapLatest { uri ->
            if (uri != null) app.documentRepository.observeChunkCount(uri) else flowOf(0)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            saveTreeUri(uri.toString())
            app.documentRepository.indexFolder(uri)
        }
    }

    fun reindex() {
        val uri = savedTreeUri.value ?: return
        viewModelScope.launch {
            app.documentRepository.indexFolder(Uri.parse(uri))
        }
    }

    private suspend fun saveTreeUri(uriString: String) {
        app.dataStore.edit { prefs -> prefs[PREF_TREE_URI] = uriString }
    }
}
