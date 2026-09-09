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
import okhttp3.Response
import okhttp3.ResponseBody
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
    fun `downloadAll handles HTTP error response`() = runTest {
        mockkConstructor(OkHttpClient.Builder::class)
        val mockClient = mockk<OkHttpClient>()
        val mockCall = mockk<Call>()
        val mockResponse = mockk<Response>()

        every { anyConstructed<OkHttpClient.Builder>().build() } returns mockClient
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns mockResponse
        every { mockResponse.isSuccessful } returns false
        every { mockResponse.code } returns 404
        every { mockResponse.message } returns "Not Found"
        every { mockResponse.body } returns null

        val mockContext = mockk<Context>()
        val filesDir = tempFolder.newFolder("models_http_error")
        every { mockContext.filesDir } returns filesDir

        try {
            val downloader = ModelDownloader(mockContext)
            val results = downloader.downloadAll().toList()

            val errorResult = results.find { it is DownloadResult.Error } as? DownloadResult.Error
            assertTrue("Expected an error result", errorResult != null)
            assertEquals("HTTP 404: Not Found", errorResult!!.message)
        } finally {
            unmockkConstructor(OkHttpClient.Builder::class)
        }
    }

    @Test
    fun `calculateSha256 exception returns empty string`() {
        val mockContext = mockk<Context>()
        val filesDir = tempFolder.newFolder("models_test")
        every { mockContext.filesDir } returns filesDir
        val downloader = ModelDownloader(mockContext)

        val method = ModelDownloader::class.java.getDeclaredMethod("calculateSha256", File::class.java)
        method.isAccessible = true

        val file = File(filesDir, "non_existent_file_for_test.txt")
        val result = method.invoke(downloader, file) as String
        assertEquals("", result)
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
