package com.minibrain.ai.llm

import com.google.ai.edge.litertlm.Engine
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import timber.log.Timber

class LlmServiceTest {

    private val logs = mutableListOf<String>()
    private val testTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs.add(message)
        }
    }

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any(), any()) } returns 0
        logs.clear()
        Timber.plant(testTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(testTree)
        unmockkAll()
    }

    @Test
    fun `initialize throws exception if model file is too small`() = runBlocking {
        val service = LlmService()

        val tempFile = File.createTempFile("model", ".bin")
        RandomAccessFile(tempFile, "rw").use { it.setLength(100_000) } // Only 100KB, less than 100MB

        try {
            service.initialize(tempFile)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("モデルファイルが不完全または存在しません"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `initialize falls back to CPU if GPU fails`() = runBlocking {
        val service = LlmService()

        val tempFile = File.createTempFile("model", ".bin")
        RandomAccessFile(tempFile, "rw").use { it.setLength(100_000_000) } // 100MB

        mockkConstructor(Engine::class)
        // First call to initialize (GPU) throws exception, second (CPU) succeeds
        every { anyConstructed<Engine>().initialize() } throws RuntimeException("GPU Failed") andThen Unit

        try {
            service.initialize(tempFile, forceCpu = false)
            assertTrue(service.isReady())
            assertTrue(logs.any { it.contains("GPU initialization failed, falling back to CPU") })
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `initialize fails if both GPU and CPU fail`() = runBlocking {
        val service = LlmService()

        val tempFile = File.createTempFile("model", ".bin")
        RandomAccessFile(tempFile, "rw").use { it.setLength(100_000_000) } // 100MB

        mockkConstructor(Engine::class)
        // Both GPU and CPU initialization fail
        every { anyConstructed<Engine>().initialize() } throws RuntimeException("Hardware Failed")

        try {
            service.initialize(tempFile, forceCpu = false)
            fail("Expected Exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("GPU/CPU 両方で失敗しました"))
            assertTrue(logs.any { it.contains("CPU initialization failed") })
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `initialize fails with correct message if forceCpu fails`() = runBlocking {
        val service = LlmService()

        val tempFile = File.createTempFile("model", ".bin")
        RandomAccessFile(tempFile, "rw").use { it.setLength(100_000_000) } // 100MB

        mockkConstructor(Engine::class)
        every { anyConstructed<Engine>().initialize() } throws RuntimeException("CPU Failed")

        try {
            service.initialize(tempFile, forceCpu = true)
            fail("Expected Exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("CPUモードでの初期化に失敗しました"))
            assertTrue(logs.any { it.contains("CPU initialization failed") })
        } finally {
            tempFile.delete()
        }
    }
}
