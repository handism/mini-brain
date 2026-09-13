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

        val file = File(filesDir, "non_existent_file_for_test.txt")
        val result = downloader.calculateSha256(file)
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

    @Test
    fun `calculateSha256 handles MessageDigest exception`() {
        val mockContext = mockk<Context>()
        val filesDir = tempFolder.newFolder("models_test_sha_ex")
        every { mockContext.filesDir } returns filesDir
        val downloader = ModelDownloader(mockContext)

        val file = File(filesDir, "test.txt").apply { writeText("dummy") }

        io.mockk.mockkStatic(java.security.MessageDigest::class)
        try {
            every { java.security.MessageDigest.getInstance(any()) } throws java.security.NoSuchAlgorithmException("Mocked algorithm error")
            val result = downloader.calculateSha256(file)
            assertEquals("", result)
        } finally {
            io.mockk.unmockkStatic(java.security.MessageDigest::class)
        }
    }

    @Test
    fun `moveFile exception returns error message`() {
        val mockContext = mockk<Context>()
        val filesDir = tempFolder.newFolder("models_test_move")
        every { mockContext.filesDir } returns filesDir
        val downloader = ModelDownloader(mockContext)

        val src = File(filesDir, "src.txt")
        val dst = File(filesDir, "dst.txt")

        io.mockk.mockkStatic(java.nio.file.Files::class)
        try {
            every { java.nio.file.Files.move(any<java.nio.file.Path>(), any<java.nio.file.Path>(), *anyVararg()) } throws java.io.IOException("Mocked move error")
            val result = downloader.moveFile(src, dst)
            assertTrue(result?.contains("Mocked move error") == true)
        } finally {
            io.mockk.unmockkStatic(java.nio.file.Files::class)
        }
    }
}
