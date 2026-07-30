package com.minibrain.ai.embed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class EmbedderServiceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test floatArrayToBytes and bytesToFloatArray`() {
        val original = floatArrayOf(1.0f, -2.5f, 3.14159f, 0.0f)
        val bytes = EmbedderService.floatArrayToBytes(original)
        val result = EmbedderService.bytesToFloatArray(bytes)

        assertEquals(original.size, result.size)
        for (i in original.indices) {
            assertEquals(original[i], result[i], 0.0001f)
        }
    }

    @Test
    fun `test meanPoolAndNormalize via reflection`() {
        val service = EmbedderService()

        val hidden = arrayOf(
            floatArrayOf(1.0f, 2.0f, 3.0f),
            floatArrayOf(4.0f, 5.0f, 6.0f),
            floatArrayOf(7.0f, 8.0f, 9.0f)
        )
        val mask = longArrayOf(1, 1, 0)

        val method = EmbedderService::class.java.getDeclaredMethod("meanPoolAndNormalize", Array<FloatArray>::class.java, LongArray::class.java)
        method.isAccessible = true

        val result = method.invoke(service, hidden, mask) as FloatArray

        val expectedUnnormalized = floatArrayOf(2.5f, 3.5f, 4.5f)
        var norm = 0f
        for (v in expectedUnnormalized) norm += v * v
        norm = sqrt(norm)

        assertEquals(3, result.size)
        assertEquals(expectedUnnormalized[0] / norm, result[0], 0.0001f)
        assertEquals(expectedUnnormalized[1] / norm, result[1], 0.0001f)
        assertEquals(expectedUnnormalized[2] / norm, result[2], 0.0001f)
    }

    @Test
    fun `test state transitions`() {
        val service = EmbedderService()
        assertFalse(service.isReady())
        service.close() // should not crash
    }

    @Test(expected = IllegalArgumentException::class)
    fun `initialize fails with missing model file`() = runTest {
        val service = EmbedderService()
        val modelFile = File("/path/that/does/not/exist/model.onnx")
        val tokenizerFile = File("/path/that/does/not/exist/tokenizer.json")
        service.initialize(modelFile, tokenizerFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `initialize fails with too small model file`() = runTest {
        val service = EmbedderService()

        val modelFile = File.createTempFile("model", ".onnx")
        modelFile.deleteOnExit()
        val tokenizerFile = File.createTempFile("tokenizer", ".json")
        tokenizerFile.deleteOnExit()

        service.initialize(modelFile, tokenizerFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `embed fails when not initialized`() = runTest {
        val service = EmbedderService()
        service.embed("test")
    }
}
