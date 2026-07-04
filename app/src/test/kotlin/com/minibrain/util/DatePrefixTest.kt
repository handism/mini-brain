package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatePrefixTest {

    @Test
    fun testBuild_withDateAndBody() {
        val result = DatePrefix.build("2023-10-25", "Some body text")
        assertEquals("[日付: 2023-10-25] Some body text", result)
    }

    @Test
    fun testBuild_withDateOnly() {
        val result = DatePrefix.build("2023-10-25", null)
        assertEquals("[日付: 2023-10-25] ", result)
    }

    @Test
    fun testBuild_withBodyOnly() {
        val result = DatePrefix.build(null, "Some body text")
        assertEquals("Some body text", result)
    }

    @Test
    fun testBuild_withBlankDateAndBody() {
        val result = DatePrefix.build("   ", "Some body text")
        assertEquals("Some body text", result)
    }

    @Test
    fun testBuild_bothNull() {
        val result = DatePrefix.build(null, null)
        assertEquals("", result)
    }

    @Test
    fun testSplit_withPrefix() {
        val (date, body) = DatePrefix.split("[日付: 2023-10-25] Some body text")
        assertEquals("2023-10-25", date)
        assertEquals("Some body text", body)
    }

    @Test
    fun testSplit_withPrefixAndExtraSpaces() {
        val (date, body) = DatePrefix.split("[日付:  2023-10-25]   Some body text")
        assertEquals("2023-10-25", date)
        assertEquals("Some body text", body)
    }

    @Test
    fun testSplit_withoutPrefix() {
        val (date, body) = DatePrefix.split("Some body text without prefix")
        assertEquals(null, date)
        assertEquals("Some body text without prefix", body)
    }

    @Test
    fun testHasPrefix_true() {
        assertTrue(DatePrefix.hasPrefix("[日付: 2023-10-25] Some body text"))
    }

    @Test
    fun testHasPrefix_withLeadingSpaces() {
        assertTrue(DatePrefix.hasPrefix("  [日付: 2023-10-25] Some body text"))
    }

    @Test
    fun testHasPrefix_false() {
        assertFalse(DatePrefix.hasPrefix("Some body text"))
    }
}
