package com.minibrain.ui.vm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.minibrain.MiniBrainApp
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.llm.ModelDownloader
import com.minibrain.di.AppContainer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import timber.log.Timber

import com.minibrain.dataStore

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(android.util.Log::class)
        every { android.util.Log.isLoggable(any(), any()) } returns true
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0

        // Timber handles its own forest, planting a fake tree or just ignoring it
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // do nothing
            }
        })
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Timber.uprootAll()
        unmockkAll()
    }

    @Test
    fun `initializeServices failure transitions to Failure state`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[any<Preferences.Key<Boolean>>()] } returns false
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val embedderService = mockk<EmbedderService>(relaxed = true)
        val llmService = mockk<LlmService>(relaxed = true)
        val modelDownloader = mockk<ModelDownloader>(relaxed = true)

        every { container.embedderService } returns embedderService
        every { container.llmService } returns llmService
        every { container.modelDownloader } returns modelDownloader

        every { modelDownloader.isAllReady(any()) } returns true
        every { modelDownloader.embedderModelFile } returns File("embedder.onnx")
        every { modelDownloader.tokenizerModelFile } returns File("tokenizer.json")
        every { modelDownloader.llmFile(any()) } returns File("model.bin")

        val errorMessage = "GPU out of memory"
        coEvery { llmService.initialize(any(), any()) } throws RuntimeException(errorMessage)

        val viewModel = OnboardingViewModel(app, testDispatcher)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected failure state but got $state", state is OnboardingUiState.Failure)
        val failureState = state as OnboardingUiState.Failure
        assertEquals(errorMessage, failureState.detail)
        assertTrue(failureState.message.contains("端末のメモリ不足の可能性があります"))
        assertTrue(failureState.canTryCpu)
    }

    @Test
    fun `initializeServices failure in embedder transitions to Failure state`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[any<Preferences.Key<Boolean>>()] } returns false
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val embedderService = mockk<EmbedderService>(relaxed = true)
        val llmService = mockk<LlmService>(relaxed = true)
        val modelDownloader = mockk<ModelDownloader>(relaxed = true)

        every { container.embedderService } returns embedderService
        every { container.llmService } returns llmService
        every { container.modelDownloader } returns modelDownloader

        every { modelDownloader.isAllReady(any()) } returns true
        every { modelDownloader.embedderModelFile } returns File("embedder.onnx")
        every { modelDownloader.tokenizerModelFile } returns File("tokenizer.json")
        every { modelDownloader.llmFile(any()) } returns File("model.bin")

        val errorMessage = "Embedder init failed"
        coEvery { embedderService.initialize(any(), any()) } throws RuntimeException(errorMessage)

        val viewModel = OnboardingViewModel(app, testDispatcher)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected failure state but got $state", state is OnboardingUiState.Failure)
        val failureState = state as OnboardingUiState.Failure
        assertEquals(errorMessage, failureState.detail)
        assertTrue(failureState.canTryCpu)
    }

    @Test
    fun `checkAndPrepare when crashed last time shows Failure state`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[any<Preferences.Key<Boolean>>()] } returns true
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val modelDownloader = mockk<ModelDownloader>(relaxed = true)
        every { container.modelDownloader } returns modelDownloader
        every { modelDownloader.isAllReady(any()) } returns true

        val viewModel = OnboardingViewModel(app, testDispatcher)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected failure state but got $state", state is OnboardingUiState.Failure)
        val failureState = state as OnboardingUiState.Failure
        assertTrue(failureState.message.contains("GPU"))
        assertTrue(failureState.canTryCpu)
    }

    @Test
    fun `checkAndPrepare when models not ready shows Required state`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[any<Preferences.Key<Boolean>>()] } returns false
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val modelDownloader = mockk<ModelDownloader>(relaxed = true)
        every { container.modelDownloader } returns modelDownloader
        every { modelDownloader.isAllReady(any()) } returns false

        val viewModel = OnboardingViewModel(app, testDispatcher)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected Required state but got $state", state is OnboardingUiState.Required)
    }

    @Test
    fun `retryWithCpu failure transitions to Failure state with canTryCpu false`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[any<Preferences.Key<Boolean>>()] } returns false
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val embedderService = mockk<EmbedderService>(relaxed = true)
        val llmService = mockk<LlmService>(relaxed = true)
        val modelDownloader = mockk<ModelDownloader>(relaxed = true)

        every { container.embedderService } returns embedderService
        every { container.llmService } returns llmService
        every { container.modelDownloader } returns modelDownloader

        every { modelDownloader.isAllReady(any()) } returns true
        every { modelDownloader.embedderModelFile } returns File("embedder.onnx")
        every { modelDownloader.tokenizerModelFile } returns File("tokenizer.json")
        every { modelDownloader.llmFile(any()) } returns File("model.bin")

        val errorMessage = "CPU initialization failed"
        coEvery { llmService.initialize(any(), forceCpu = true) } throws RuntimeException(errorMessage)

        val viewModel = OnboardingViewModel(app, testDispatcher)
        // 画面で CPU の再試行を押せるのは、起動時のチェックが終わった後
        advanceUntilIdle()

        viewModel.retryWithCpu()

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected failure state but got $state", state is OnboardingUiState.Failure)
        val failureState = state as OnboardingUiState.Failure
        assertEquals(errorMessage, failureState.detail)
        assertTrue(!failureState.canTryCpu)
    }

    @Test
    fun `cancelDownload stops the download and returns to Required`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[any<Preferences.Key<Boolean>>()] } returns false
        every { testDataStore.data } returns flowOf(testPrefs)
        coEvery { testDataStore.updateData(any()) } returns testPrefs

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val modelDownloader = mockk<ModelDownloader>(relaxed = true)
        every { container.modelDownloader } returns modelDownloader
        every { modelDownloader.isAllReady(any()) } returns false
        // 終わらないダウンロード
        every { modelDownloader.downloadAll(any()) } returns kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.awaitCancellation()
        }

        val viewModel = OnboardingViewModel(app, testDispatcher)
        advanceUntilIdle()
        viewModel.startDownload()
        advanceUntilIdle()
        assertTrue(viewModel.state.value is OnboardingUiState.Downloading)

        viewModel.cancelDownload()
        advanceUntilIdle()
        assertEquals(OnboardingUiState.Required, viewModel.state.value)
    }

    @Test
    fun `初期化中に画面が閉じられても失敗扱いにせず、クラッシュ検知フラグを戻す`() = runTest {
        val app = mockk<MiniBrainApp>(relaxed = true)
        val container = mockk<AppContainer>(relaxed = true)
        every { app.container } returns container

        val testDataStore = mockk<DataStore<Preferences>>(relaxed = true)
        val testPrefs = mockk<Preferences>(relaxed = true)
        every { testPrefs[any<Preferences.Key<Boolean>>()] } returns false
        every { testDataStore.data } returns flowOf(testPrefs)
        val written = mutableListOf<Boolean?>()
        coEvery { testDataStore.updateData(any()) } coAnswers {
            val transform = firstArg<suspend (Preferences) -> Preferences>()
            val updated = transform(androidx.datastore.preferences.core.emptyPreferences())
            written += updated[androidx.datastore.preferences.core.booleanPreferencesKey("init_in_progress")]
            updated
        }

        mockkStatic("com.minibrain.MiniBrainAppKt")
        every { app.dataStore } returns testDataStore

        val embedderService = mockk<EmbedderService>(relaxed = true)
        val llmService = mockk<LlmService>(relaxed = true)
        val modelDownloader = mockk<ModelDownloader>(relaxed = true)
        every { container.embedderService } returns embedderService
        every { container.llmService } returns llmService
        every { container.modelDownloader } returns modelDownloader
        every { modelDownloader.isAllReady(any()) } returns true
        every { modelDownloader.llmFile(any()) } returns File("model.bin")
        val loading = CompletableDeferred<Unit>()
        coEvery { llmService.initialize(any(), any()) } coAnswers { loading.await() }

        val viewModel = OnboardingViewModel(app, testDispatcher)
        advanceUntilIdle()
        assertEquals(OnboardingUiState.Initializing, viewModel.state.value)

        viewModel.viewModelScope.cancel()
        advanceUntilIdle()

        assertEquals(OnboardingUiState.Initializing, viewModel.state.value)
        assertEquals(listOf<Boolean?>(true, false), written)
    }
}
