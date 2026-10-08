package com.minibrain.ui.vm

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.ai.llm.DownloadResult
import com.minibrain.ai.llm.LlmModel
import com.minibrain.ai.llm.selectedLlmModelFlow
import com.minibrain.ai.llm.setSelectedLlmModel
import com.minibrain.data.repo.IndexingState
import com.minibrain.dataStore
import com.minibrain.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

private val PREF_TREE_URI_SETTINGS = stringPreferencesKey("tree_uri")
private val PREF_SHOW_SEARCH_LOG = booleanPreferencesKey("show_search_log")

/** LLM の切り替えの進み具合（ADR-057）。 */
sealed interface ModelSwitchState {
    data object Idle : ModelSwitchState
    /** [fraction] は 0〜1。大きさが分からない間は null */
    data class Downloading(val target: LlmModel, val fraction: Float?) : ModelSwitchState
    data class Loading(val target: LlmModel) : ModelSwitchState
    data class Failed(val target: LlmModel, val detail: String?) : ModelSwitchState
}

/** 検索ログは開発者向けなので、普段使いでは出さない。 */
internal const val SHOW_SEARCH_LOG_DEFAULT = false

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        private const val TAG = "SettingsViewModel"
    }

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

    val llmModel: StateFlow<LlmModel> = app.dataStore.selectedLlmModelFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LlmModel.DEFAULT)

    private val _modelSwitch = MutableStateFlow<ModelSwitchState>(ModelSwitchState.Idle)
    val modelSwitch: StateFlow<ModelSwitchState> = _modelSwitch

    fun llmFile(model: LlmModel) = app.container.modelDownloader.llmFile(model)

    /**
     * LLM を切り替える。ダウンロード → 読み込み → 選択を保存 → 前のモデルのファイルを削除、の順。
     * 読み込みに失敗したら前のモデルを読み込み直し、選択は変えない（ダウンロードしたファイルは再試行用に残す）。
     * ダウンロードは画面を離れると止まるが、次に選んだときに続きから再開する。
     */
    fun switchLlmModel(target: LlmModel) {
        val current = llmModel.value
        val busy = _modelSwitch.value is ModelSwitchState.Downloading || _modelSwitch.value is ModelSwitchState.Loading
        if (target == current || busy) return
        val downloader = app.container.modelDownloader
        viewModelScope.launch {
            _modelSwitch.value = ModelSwitchState.Downloading(target, null)
            var error: String? = null
            downloader.downloadLlm(target).collect { result ->
                when (result) {
                    is DownloadResult.Progress -> _modelSwitch.value =
                        ModelSwitchState.Downloading(target, result.progress.fraction.takeIf { result.progress.totalBytes > 0 })
                    is DownloadResult.Error -> error = result.message
                    is DownloadResult.Done -> Unit
                }
            }
            if (error != null) {
                _modelSwitch.value = ModelSwitchState.Failed(target, error)
                return@launch
            }

            _modelSwitch.value = ModelSwitchState.Loading(target)
            val llm = app.container.llmService
            val loaded = runCatchingCancellable { llm.initialize(downloader.llmFile(target)) }
            if (loaded.isFailure) {
                Timber.tag(TAG).w(loaded.exceptionOrNull(), "failed to load $target, restoring $current")
                runCatchingCancellable { llm.initialize(downloader.llmFile(current)) }
                _modelSwitch.value = ModelSwitchState.Failed(target, loaded.exceptionOrNull()?.localizedMessage)
                return@launch
            }
            app.dataStore.setSelectedLlmModel(target)
            downloader.deleteLlm(current)
            _modelSwitch.value = ModelSwitchState.Idle
        }
    }
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
