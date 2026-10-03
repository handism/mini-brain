package com.minibrain.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class CoroutinesTest {

    @Test
    fun `wraps ordinary exceptions into failure`() {
        val result = runCatchingCancellable { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `returns success value`() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
    }

    @Test(expected = CancellationException::class)
    fun `rethrows cancellation`() {
        runCatchingCancellable { throw CancellationException("cancelled") }
    }

    @Test
    fun `timeout propagates to withTimeoutOrNull instead of being swallowed`() = runBlocking {
        val result = withTimeoutOrNull(10) {
            runCatchingCancellable { delay(1_000); "done" }.getOrDefault("swallowed")
        }
        assertNull(result)
    }

    @Test(expected = TimeoutCancellationException::class)
    fun `timeout is not reported as failure`(): Unit = runBlocking {
        withTimeout(10) { runCatchingCancellable { delay(1_000) } }
    }
}
