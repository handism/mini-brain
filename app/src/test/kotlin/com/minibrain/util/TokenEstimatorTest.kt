package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun testEstimateEmptyString() {
        val result = TokenEstimator.estimate("")
        assertEquals(5, result) // 0/3 + 0/4 + 5 = 5
    }

    @Test
    fun testEstimateAsciiString() {
        val text = "hello world!" // 12 characters, all <= 127
        val result = TokenEstimator.estimate(text)
        assertEquals(8, result) // 0/3 + 12/4 + 5 = 8
    }

    @Test
    fun testEstimateNonAsciiString() {
        val text = "こんにちは" // 5 characters, all > 127
        val result = TokenEstimator.estimate(text)
        assertEquals(6, result) // 5/3 + 0/4 + 5 = 1 + 0 + 5 = 6
    }

    @Test
    fun testEstimateMixedString() {
        val text = "helloこんにちは" // 5 ASCII, 5 Non-ASCII
        val result = TokenEstimator.estimate(text)
        assertEquals(7, result) // 5/3 + 5/4 + 5 = 1 + 1 + 5 = 7
    }

    @Test
    fun testEstimateVarargEmpty() {
        val result = TokenEstimator.estimate(*emptyArray<String>())
        assertEquals(5, result) // 0/3 + 0/4 + 5 = 5
    }

    @Test
    fun testEstimateVarargAsciiStrings() {
        val result = TokenEstimator.estimate("hello", " world!") // 12 characters total
        assertEquals(8, result) // 0/3 + 12/4 + 5 = 8
    }

    @Test
    fun testEstimateVarargNonAsciiStrings() {
        val result = TokenEstimator.estimate("こんに", "ちは") // 5 characters total
        assertEquals(6, result) // 5/3 + 0/4 + 5 = 6
    }

    @Test
    fun testEstimateVarargMixedStrings() {
        val result = TokenEstimator.estimate("hello", "こんにちは") // 5 ASCII, 5 Non-ASCII
        assertEquals(7, result) // 5/3 + 5/4 + 5 = 7
    }

    @Test
    fun testEstimateVarargMixedWithinString() {
        val result = TokenEstimator.estimate("helloこんにちは", "world世界") // ASCII: 5 + 5 = 10, Non-ASCII: 5 + 2 = 7
        // 7/3 = 2
        // 10/4 = 2
        // 2 + 2 + 5 = 9
        assertEquals(9, result)
    }
}
