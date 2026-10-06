package com.minibrain.ai.llm

import com.minibrain.util.JsonArrayText
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonArrayGenerationTest {

    private val llmService = mockk<LlmService>()

    @Test
    fun `stops reading once the array is closed`() = runTest {
        var emittedAfterClose = 0
        every { llmService.generateStream(any()) } returns flow {
            emit("[\"a\", ")
            emit("\"b\"]")
            // 配列の後に繰り返しが続くケース。ここは読まれない
            repeat(1000) {
                emittedAfterClose++
                emit(" \"b\"")
            }
        }

        val raw = llmService.generateJsonArray("prompt", timeoutMs = 1_000)

        assertEquals("[\"a\", \"b\"]", raw)
        assertEquals(0, emittedAfterClose)
    }

    @Test
    fun `returns whole text when generation ends without closing`() = runTest {
        every { llmService.generateStream(any()) } returns flowOf("[1, ", "2")

        assertEquals("[1, 2", llmService.generateJsonArray("prompt", timeoutMs = 1_000))
    }

    @Test
    fun `returns null when the array does not close in time`() = runTest {
        every { llmService.generateStream(any()) } returns flow {
            emit("[")
            while (true) {
                delay(100)
                emit("\"x\", ")
            }
        }

        assertNull(llmService.generateJsonArray("prompt", timeoutMs = 5_000))
    }

    @Test
    fun `returns null when the model never answers`() = runTest {
        every { llmService.generateStream(any()) } returns flow { awaitCancellation() }

        assertNull(llmService.generateJsonArray("prompt", timeoutMs = 5_000))
    }

    @Test
    fun `isClosed needs a closing bracket after the opening one`() {
        assertTrue(JsonArrayText.isClosed("前置き [1, 2] 後書き"))
        assertFalse(JsonArrayText.isClosed("[1, 2"))
        assertFalse(JsonArrayText.isClosed("]1, 2["))
        assertFalse(JsonArrayText.isClosed("no array"))
    }
}
