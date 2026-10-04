package com.minibrain.ui.vm

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minibrain.MiniBrainApp
import com.minibrain.data.repo.IndexingState
import com.minibrain.dataStore
import com.minibrain.eval.EvalMetrics
import com.minibrain.eval.EvalReport
import com.minibrain.eval.EvalResult
import com.minibrain.eval.EvalRunner
import com.minibrain.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PREF_TREE_URI_EVAL = stringPreferencesKey("tree_uri")
private val PREF_EVAL_FILE_URI = stringPreferencesKey("eval_file_uri")

/** 評価は上位 10 件で測る（SearchPipeline.RERANK_TOP_K と同じ） */
private const val EVAL_K = 10

sealed interface EvalUiState {
    data object Idle : EvalUiState
    data class Running(val current: Int, val total: Int, val query: String) : EvalUiState
    data class Done(val result: EvalResult, val unknownPaths: List<String>, val report: String) : EvalUiState
    data class Failed(val message: String) : EvalUiState
}

class EvalViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp

    private val evalFileUri = app.dataStore.data
        .map { prefs -> prefs[PREF_EVAL_FILE_URI] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 選んだ評価セットのファイル名。読めなくなっていたら null */
    val evalFileName: StateFlow<String?> = evalFileUri
        .map { uri -> uri?.let { displayName(Uri.parse(it)) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val indexingState: StateFlow<IndexingState> = app.container.documentRepository.indexingState

    private val _state = MutableStateFlow<EvalUiState>(EvalUiState.Idle)
    val state: StateFlow<EvalUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun selectFile(uri: Uri) {
        // 次に開いたときもそのまま再実行できるよう、読み取り権限を持ち続ける
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch {
            app.dataStore.edit { prefs -> prefs[PREF_EVAL_FILE_URI] = uri.toString() }
        }
    }

    fun run() {
        if (_state.value is EvalUiState.Running) return
        job = viewModelScope.launch {
            _state.value = runCatchingCancellable { evaluate() }
                .getOrElse { EvalUiState.Failed(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = EvalUiState.Idle
    }

    private suspend fun evaluate(): EvalUiState {
        val treeUri = app.dataStore.data.first()[PREF_TREE_URI_EVAL]
            ?: return EvalUiState.Failed("フォルダが選ばれていません")
        val fileUri = evalFileUri.value?.let(Uri::parse)
            ?: return EvalUiState.Failed("評価セットが選ばれていません")
        val fileName = displayName(fileUri) ?: "評価セット"

        val cases = withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openInputStream(fileUri)) { "評価セットを開けません" }
                .use(EvalRunner::load)
        }
        if (cases.isEmpty()) return EvalUiState.Failed("評価セットにケースがありません")

        val indexedPaths = app.container.database.documentDao().getMinimalByTree(treeUri).map { it.relativePath }
        val unknownPaths = EvalMetrics.findUnknownPaths(cases, indexedPaths)

        val result = EvalRunner(app.container.searchPipeline).run(treeUri, cases, EVAL_K) { current, total ->
            cases.getOrNull(current)?.let { _state.value = EvalUiState.Running(current, total, it.query) }
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date())
        val report = EvalReport.toMarkdown(result, "検索評価 $stamp（$fileName）", unknownPaths)
        return EvalUiState.Done(result, unknownPaths, report)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
