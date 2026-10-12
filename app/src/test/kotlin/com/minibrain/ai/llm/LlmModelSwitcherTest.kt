package com.minibrain.ai.llm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LlmModelSwitcherTest {

    private val testDispatcher = StandardTestDispatcher()
    private val downloader = mockk<ModelDownloader>()
    private val llm = mockk<LlmService>(relaxed = true)
    private val dataStore = mockk<DataStore<Preferences>>().also {
        coEvery { it.updateData(any()) } returns mockk(relaxed = true)
    }

    init {
        every { downloader.llmFile(LlmModel.E2B) } returns File("e2b")
        every { downloader.llmFile(LlmModel.E4B) } returns File("e4b")
        every { downloader.deleteLlm(any()) } returns Unit
    }

    private fun switcher(scope: CoroutineScope = CoroutineScope(testDispatcher)) =
        LlmModelSwitcher(scope, downloader, llm, dataStore)

    @Test
    fun `モデルを切り替えると読み込んでから選択を保存し、前のモデルを消す`() = runTest(testDispatcher) {
        every { downloader.downloadLlm(LlmModel.E4B) } returns flowOf(DownloadResult.Done(File("e4b")))
        val switcher = switcher()

        switcher.switch(LlmModel.E4B, current = LlmModel.E2B)
        advanceUntilIdle()

        coVerify { llm.initialize(File("e4b"), any()) }
        coVerify { dataStore.updateData(any()) }
        verify { downloader.deleteLlm(LlmModel.E2B) }
        assertEquals(ModelSwitchState.Idle, switcher.state.value)
    }

    @Test
    fun `CPU モードで読み込んでいたら切り替え後も CPU で読み込む`() = runTest(testDispatcher) {
        every { downloader.downloadLlm(LlmModel.E4B) } returns flowOf(DownloadResult.Done(File("e4b")))
        every { llm.forceCpu } returns true
        val switcher = switcher()

        switcher.switch(LlmModel.E4B, current = LlmModel.E2B)
        advanceUntilIdle()

        coVerify { llm.initialize(File("e4b"), true) }
        assertEquals(ModelSwitchState.Idle, switcher.state.value)
    }

    @Test
    fun `読み込みに失敗したら前のモデルに戻し、選択もファイルも変えない`() = runTest(testDispatcher) {
        every { downloader.downloadLlm(LlmModel.E4B) } returns flowOf(DownloadResult.Done(File("e4b")))
        coEvery { llm.initialize(File("e4b"), any()) } throws RuntimeException("out of memory")
        val switcher = switcher()

        switcher.switch(LlmModel.E4B, current = LlmModel.E2B)
        advanceUntilIdle()

        coVerify { llm.initialize(File("e2b"), any()) }
        coVerify(exactly = 0) { dataStore.updateData(any()) }
        verify(exactly = 0) { downloader.deleteLlm(any()) }
        assertEquals(ModelSwitchState.Failed(LlmModel.E4B, "out of memory"), switcher.state.value)
    }

    @Test
    fun `ダウンロードに失敗したら読み込まない`() = runTest(testDispatcher) {
        every { downloader.downloadLlm(LlmModel.E4B) } returns flowOf(
            DownloadResult.Progress(DownloadProgress("e4b", 10, 100)),
            DownloadResult.Error("HTTP 500"),
        )
        val switcher = switcher()

        switcher.switch(LlmModel.E4B, current = LlmModel.E2B)
        advanceUntilIdle()

        coVerify(exactly = 0) { llm.initialize(any(), any()) }
        assertEquals(ModelSwitchState.Failed(LlmModel.E4B, "HTTP 500"), switcher.state.value)
    }

    @Test
    fun `切り替え中は重ねて切り替えない`() = runTest(testDispatcher) {
        every { downloader.downloadLlm(LlmModel.E4B) } returns flowOf(DownloadResult.Done(File("e4b")))
        val switcher = switcher()

        switcher.switch(LlmModel.E4B, current = LlmModel.E2B)
        switcher.switch(LlmModel.E4B, current = LlmModel.E2B)
        advanceUntilIdle()

        verify(exactly = 1) { downloader.downloadLlm(LlmModel.E4B) }
    }

    @Test
    fun `読み込み中に止められても、選択の保存と前のモデルの削除まで終える`() = runTest(testDispatcher) {
        every { downloader.downloadLlm(LlmModel.E4B) } returns flowOf(DownloadResult.Done(File("e4b")))
        val loading = CompletableDeferred<Unit>()
        coEvery { llm.initialize(File("e4b"), any()) } coAnswers { loading.await() }
        val scope = CoroutineScope(testDispatcher + Job())
        val switcher = switcher(scope)

        switcher.switch(LlmModel.E4B, current = LlmModel.E2B)
        advanceUntilIdle()
        assertEquals(ModelSwitchState.Loading(LlmModel.E4B), switcher.state.value)

        scope.cancel()
        loading.complete(Unit)
        advanceUntilIdle()

        coVerify { dataStore.updateData(any()) }
        verify { downloader.deleteLlm(LlmModel.E2B) }
        assertEquals(ModelSwitchState.Idle, switcher.state.value)
    }
}
