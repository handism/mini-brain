package com.minibrain.ui.vm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.minibrain.MiniBrainApp
import com.minibrain.ai.embed.EmbedderService
import com.minibrain.ai.llm.LlmService
import com.minibrain.ai.llm.ModelDownloader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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

        every { app.embedderService } returns embedderService
        every { app.llmService } returns llmService
        every { app.modelDownloader } returns modelDownloader

        every { modelDownloader.isAllReady() } returns true
        every { modelDownloader.embedderModelFile } returns File("embedder.onnx")
        every { modelDownloader.tokenizerModelFile } returns File("tokenizer.json")
        every { modelDownloader.llmModelFile } returns File("model.bin")

        val errorMessage = "GPU out of memory"
        coEvery { llmService.initialize(any(), any()) } throws RuntimeException(errorMessage)

        val viewModel = OnboardingViewModel(app)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected failure state but got $state", state is OnboardingUiState.Failure)
        val failureState = state as OnboardingUiState.Failure
        assertTrue(failureState.message.contains(errorMessage))
        assertTrue(failureState.message.contains("端末のメモリ不足の可能性があります"))
        assertTrue(failureState.canTryCpu)
    }
}
