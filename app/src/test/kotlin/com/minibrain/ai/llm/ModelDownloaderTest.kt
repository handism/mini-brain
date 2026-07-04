package com.minibrain.ai.llm

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `downloadAll catches Exception and emits Error`() = runTest {
        val mockContext = mockk<Context>()
        val filesDir = tempFolder.newFolder("models")
        every { mockContext.filesDir } returns filesDir

        val downloader = spyk(ModelDownloader(mockContext))
        every { downloader.isEmbedderReady() } throws RuntimeException("Simulated exception")

        val results = downloader.downloadAll().toList()

        assertTrue(results.size == 1)
        assertTrue(results[0] is DownloadResult.Error)
        val error = results[0] as DownloadResult.Error
        assertEquals("エラー: Simulated exception", error.message)
    }
}
