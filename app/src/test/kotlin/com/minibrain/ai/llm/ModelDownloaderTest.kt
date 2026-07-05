package com.minibrain.ai.llm

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `downloadAll handles OkHttp IOException gracefully`() = runTest {
        mockkConstructor(OkHttpClient.Builder::class)
        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()

        every { anyConstructed<OkHttpClient.Builder>().build() } returns mockClient
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws IOException("Mocked network error")

        val mockContext = mockk<Context>()
        val filesDir = tempFolder.newFolder("models")
        every { mockContext.filesDir } returns filesDir

        try {
            val downloader = ModelDownloader(mockContext)
            val results = downloader.downloadAll().toList()

            val errorResult = results.find { it is DownloadResult.Error } as? DownloadResult.Error
            assertTrue(errorResult != null)
            assertTrue(errorResult!!.message.contains("Mocked network error"))
        } finally {
            unmockkConstructor(OkHttpClient.Builder::class)
        }
    }

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
