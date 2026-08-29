package com.minibrain.ai.llm

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

class ReadinessCheckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkConstructor(FileInputStream::class)
        every { anyConstructed<FileInputStream>().read(any<ByteArray>()) } returns -1
        every { anyConstructed<FileInputStream>().read(any<ByteArray>(), any(), any()) } returns -1
        every { anyConstructed<FileInputStream>().close() } returns Unit

        mockkStatic(MessageDigest::class)

        // Mock OkHttpClient.Builder to avoid "problem accessing trust store" error on mocked FileInputStream
        mockkConstructor(OkHttpClient.Builder::class)
        val mockClient = mockk<OkHttpClient>(relaxed = true)
        every { anyConstructed<OkHttpClient.Builder>().build() } returns mockClient
    }

    @After
    fun teardown() {
        unmockkStatic(MessageDigest::class)
        unmockkConstructor(FileInputStream::class)
        unmockkConstructor(OkHttpClient.Builder::class)
    }

    @Test
    fun testReadinessChecks() {
        val mockContext = mockk<Context>()
        every { mockContext.filesDir } returns tempFolder.root

        val downloader = ModelDownloader(mockContext)

        val mockDigest = mockk<MessageDigest>()
        every { MessageDigest.getInstance("SHA-256") } returns mockDigest

        // 1. Initial State
        assertFalse(downloader.isLlmReady())
        assertFalse(downloader.isEmbedderReady())
        assertFalse(downloader.isTokenizerReady())
        assertFalse(downloader.isAllReady())

        fun createMockFile(file: File, size: Long) {
            val raf = RandomAccessFile(file, "rw")
            raf.use { it.setLength(size) }
        }

        // Get actual constants using reflection against the ModelDownloader class (since they are const vals, they compile to static fields on the enclosing class)
        val downloaderClass = ModelDownloader::class.java

        val getConstLong = { name: String ->
            val field = downloaderClass.getDeclaredField(name)
            field.isAccessible = true
            field.getLong(null)
        }
        val getConstString = { name: String ->
            val field = downloaderClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(null) as String
        }

        val minLlmSize = getConstLong("MIN_LLM_SIZE")
        val minEmbedderSize = getConstLong("MIN_EMBEDDER_SIZE")
        val minTokenizerSize = getConstLong("MIN_TOKENIZER_SIZE")

        val expectedLlmHash = getConstString("LLM_SHA256")
        val expectedEmbedderHash = getConstString("EMBEDDER_SHA256")
        val expectedTokenizerHash = getConstString("TOKENIZER_SHA256")

        // 2. LLM file with valid size and hash
        createMockFile(downloader.llmModelFile, minLlmSize)
        every { mockDigest.digest() } returns expectedLlmHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(downloader.isLlmReady())

        // 3. Embedder file with valid size and hash
        createMockFile(downloader.embedderModelFile, minEmbedderSize)
        every { mockDigest.digest() } returns expectedEmbedderHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(downloader.isEmbedderReady())

        // 4. Tokenizer file with valid size and hash
        createMockFile(downloader.tokenizerModelFile, minTokenizerSize)
        every { mockDigest.digest() } returns expectedTokenizerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertTrue(downloader.isTokenizerReady())

        // 5. Test isAllReady when all are ready
        every { mockDigest.digest() } returnsMany listOf(
            expectedLlmHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            expectedEmbedderHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            expectedTokenizerHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        )
        assertTrue(downloader.isAllReady())

        // 6. Test invalid size for LLM
        createMockFile(downloader.llmModelFile, minLlmSize - 1)
        assertFalse(downloader.isLlmReady())
        assertFalse(downloader.isAllReady())

        // 7. Test invalid hash
        createMockFile(downloader.llmModelFile, minLlmSize)
        every { mockDigest.digest() } returns "0000000000000000000000000000000000000000000000000000000000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertFalse(downloader.isLlmReady())
    }
}
