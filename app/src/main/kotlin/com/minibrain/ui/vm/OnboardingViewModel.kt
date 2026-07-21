package com.minibrain.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.minibrain.MiniBrainApp
import com.minibrain.ai.llm.DownloadResult
import com.minibrain.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

sealed class OnboardingUiState {
    object Checking : OnboardingUiState()
    object AlreadyReady : OnboardingUiState()
    object Required : OnboardingUiState()
    data class Downloading(val llmFraction: Float, val embedderFraction: Float, val label: String) : OnboardingUiState()
    object Initializing : OnboardingUiState()
    object Ready : OnboardingUiState()
    data class Failure(val message: String, val canTryCpu: Boolean = false) : OnboardingUiState()
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MiniBrainApp
    private val downloader = app.container.modelDownloader
    private val PREF_KEY_INIT_IN_PROGRESS = booleanPreferencesKey("init_in_progress")

    private val _state = MutableStateFlow<OnboardingUiState>(OnboardingUiState.Checking)
    val state: StateFlow<OnboardingUiState> = _state

    init {
        checkAndPrepare()
    }

    fun checkAndPrepare() {
        viewModelScope.launch {
            _state.value = OnboardingUiState.Checking
            
            // 前回の起動で初期化中にクラッシュしたかチェック
            val prefs = app.dataStore.data.first()
            val crashedLastTime = prefs[PREF_KEY_INIT_IN_PROGRESS] ?: false
            
            if (downloader.isAllReady()) {
                if (crashedLastTime) {
                    _state.value = OnboardingUiState.Failure(
                        "前回の起動時に初期化中に問題が発生しました。GPUメモリ不足の可能性があります。CPUモードで試しますか？",
                        canTryCpu = true
                    )
                    // フラグをクリア
                    app.dataStore.edit { it[PREF_KEY_INIT_IN_PROGRESS] = false }
                } else {
                    initializeServices()
                }
            } else {
                _state.value = OnboardingUiState.Required
            }
        }
    }

    fun startDownload() {
        viewModelScope.launch {
            _state.value = OnboardingUiState.Downloading(0f, 0f, "接続中...")
            var llmFraction = 0f
            var embedderFraction = 0f

            downloader.downloadAll().collect { result ->
                when (result) {
                    is DownloadResult.Progress -> {
                        val p = result.progress
                        if (p.fileName.contains("litertlm")) {
                            llmFraction = p.fraction
                        } else {
                            // Embedder ONNX と tokenizer.json は合算して embedderFraction として表示
                            embedderFraction = p.fraction
                        }
                        _state.value = OnboardingUiState.Downloading(llmFraction, embedderFraction, p.fileName)
                    }
                    is DownloadResult.Done -> initializeServices()
                    is DownloadResult.Error -> _state.value = OnboardingUiState.Failure(result.message)
                }
            }
        }
    }

    fun retryWithCpu() {
        viewModelScope.launch {
            initializeServices(forceCpu = true)
        }
    }

    private suspend fun initializeServices(forceCpu: Boolean = false) {
        _state.value = OnboardingUiState.Initializing
        // 初期化開始フラグを立てる（クラッシュ検知用）
        app.dataStore.edit { it[PREF_KEY_INIT_IN_PROGRESS] = true }
        try {
            Timber.tag("Onboarding").d("Initializing Embedder...")
            app.container.embedderService.initialize(downloader.embedderModelFile, downloader.tokenizerModelFile)

            Timber.tag("Onboarding").d("Initializing LLM (forceCpu=$forceCpu)...")
            if (forceCpu) {
                app.container.llmService.initialize(downloader.llmModelFile, forceCpu = true)
            } else {
                app.container.llmService.initialize(downloader.llmModelFile)
            }

            Timber.tag("Onboarding").d("All services initialized")
            _state.value = OnboardingUiState.Ready
        } catch (e: Exception) {
            Timber.tag("Onboarding").e(e, "Initialization failed")
            _state.value = OnboardingUiState.Failure(
                "初期化に失敗しました: ${e.localizedMessage}\n" +
                "端末のメモリ不足の可能性があります。",
                canTryCpu = !forceCpu
            )
        } finally {
            // 成功・失敗どちらの場合もクラッシュ検知フラグをクリア
            app.dataStore.edit { it[PREF_KEY_INIT_IN_PROGRESS] = false }
        }
    }
}
