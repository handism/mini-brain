package com.minibrain.ai.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.minibrain.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** LLM の切り替えの進み具合（ADR-057）。 */
sealed interface ModelSwitchState {
    data object Idle : ModelSwitchState
    /** [fraction] は 0〜1。大きさが分からない間は null */
    data class Downloading(val target: LlmModel, val fraction: Float?) : ModelSwitchState
    data class Loading(val target: LlmModel) : ModelSwitchState
    data class Failed(val target: LlmModel, val detail: String?) : ModelSwitchState
}

/**
 * LLM を切り替える。ダウンロード（済んでいれば省く）→ 読み込み → 選択を保存、の順。
 * 読み込みに失敗したら前のモデルを読み込み直し、選択は変えない（ダウンロードしたファイルは再試行用に残す）。
 * 前のモデルのファイルは消さずに残し、次の切り替えをダウンロード無しで済ませる。
 * 容量を空けたいときは [delete] で使っていないモデルを消す（ADR-070）。
 *
 * 設定画面を離れても止まらないよう、アプリ全体のスコープで動かし、状態もここで持つ（ADR-062）。
 * 画面の ViewModel で動かすと、読み込み中に戻ったとき前のエンジンだけ閉じて戻し損ねる。
 */
class LlmModelSwitcher(
    private val scope: CoroutineScope,
    private val downloader: ModelDownloader,
    private val llmService: LlmService,
    private val dataStore: DataStore<Preferences>,
) {
    private companion object {
        private const val TAG = "LlmModelSwitcher"
    }

    private val _state = MutableStateFlow<ModelSwitchState>(ModelSwitchState.Idle)
    val state: StateFlow<ModelSwitchState> = _state

    private val _downloaded = MutableStateFlow<Set<LlmModel>>(emptySet())
    /** 端末にあるモデル。ファイルの有無だけを見る（ハッシュは計算しない） */
    val downloaded: StateFlow<Set<LlmModel>> = _downloaded

    fun refreshDownloaded() {
        _downloaded.value = LlmModel.entries.filter { downloader.isLlmDownloaded(it) }.toSet()
    }

    /**
     * 使っていないモデルのファイルを消す。使っているモデルと、切り替え中のモデルは消さない。
     * 切り替えに失敗したモデルを消したときは、再試行の表示も消す。
     */
    fun delete(model: LlmModel, current: LlmModel) {
        val state = _state.value
        val busy = state is ModelSwitchState.Downloading || state is ModelSwitchState.Loading
        if (model == current || busy) return
        scope.launch {
            withContext(NonCancellable) {
                runCatchingCancellable { downloader.deleteLlm(model) }
                    .onFailure { Timber.tag(TAG).w(it, "failed to delete $model") }
                if ((_state.value as? ModelSwitchState.Failed)?.target == model) _state.value = ModelSwitchState.Idle
                refreshDownloaded()
            }
        }
    }

    fun switch(target: LlmModel, current: LlmModel) {
        val busy = _state.value is ModelSwitchState.Downloading || _state.value is ModelSwitchState.Loading
        if (target == current || busy) return
        // launch 前に立てて、ディスパッチ待ちの間に重ねて押されないようにする
        _state.value = ModelSwitchState.Downloading(target, null)
        scope.launch {
            try {
                runSwitch(target, current)
            } finally {
                refreshDownloaded()
                // 想定外の例外でも「切り替え中」のまま固まらないよう、最後に必ず抜ける（ADR-066）
                val state = _state.value
                if (state is ModelSwitchState.Downloading || state is ModelSwitchState.Loading) {
                    _state.value = ModelSwitchState.Failed(target, null)
                }
            }
        }
    }

    private suspend fun runSwitch(target: LlmModel, current: LlmModel) {
        var error: String? = null
        downloader.downloadLlm(target).collect { result ->
            when (result) {
                is DownloadResult.Progress -> _state.value =
                    ModelSwitchState.Downloading(target, result.progress.fraction.takeIf { result.progress.totalBytes > 0 })
                is DownloadResult.Error -> error = result.message
                is DownloadResult.Done -> Unit
            }
        }
        if (error != null) {
            _state.value = ModelSwitchState.Failed(target, error)
            return
        }

        _state.value = ModelSwitchState.Loading(target)
        // initialize は前のエンジンを先に閉じるので、ここから先は途中で止めずに最後まで終わらせる
        withContext(NonCancellable) {
            // 「CPU モードで試す」で読み込んだ端末では GPU を試し直さない（ADR-064）
            val forceCpu = llmService.forceCpu
            val loaded = runCatchingCancellable { llmService.initialize(downloader.llmFile(target), forceCpu) }
            if (loaded.isFailure) {
                Timber.tag(TAG).w(loaded.exceptionOrNull(), "failed to load $target, restoring $current")
                runCatchingCancellable { llmService.initialize(downloader.llmFile(current), forceCpu) }
                _state.value = ModelSwitchState.Failed(target, loaded.exceptionOrNull()?.localizedMessage)
                return@withContext
            }
            // 選択を保存できなければ、次回の起動で読み込むモデルと食い違うので前のモデルに戻す
            val saved = runCatchingCancellable { dataStore.setSelectedLlmModel(target) }
            if (saved.isFailure) {
                Timber.tag(TAG).w(saved.exceptionOrNull(), "failed to save $target, restoring $current")
                runCatchingCancellable { llmService.initialize(downloader.llmFile(current), forceCpu) }
                _state.value = ModelSwitchState.Failed(target, saved.exceptionOrNull()?.localizedMessage)
                return@withContext
            }
            _state.value = ModelSwitchState.Idle
        }
    }
}
