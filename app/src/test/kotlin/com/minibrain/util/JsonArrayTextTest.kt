package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonArrayTextTest {

    @Test
    fun `extract returns null when no brackets exist`() {
        assertNull(JsonArrayText.extract("hello world"))
    }

    @Test
    fun `extract returns null when only open bracket exists`() {
        assertNull(JsonArrayText.extract("hello [ world"))
    }

    @Test
    fun `extract returns null when only close bracket exists`() {
        assertNull(JsonArrayText.extract("hello ] world"))
    }

    @Test
    fun `extract returns null when close bracket is before open bracket`() {
        assertNull(JsonArrayText.extract("] ["))
    }

    @Test
    fun `extract returns exact array when only brackets exist`() {
        assertEquals("[]", JsonArrayText.extract("[]"))
    }

    @Test
    fun `extract returns array with contents`() {
        assertEquals("[1, 2, 3]", JsonArrayText.extract("[1, 2, 3]"))
    }

    @Test
    fun `extract returns array ignoring prefix and suffix`() {
        assertEquals("[1, 2, 3]", JsonArrayText.extract("prefix\n[1, 2, 3]\nsuffix"))
    }

    @Test
    fun `extract handles nested brackets by taking the outermost ones`() {
        assertEquals("[1, [2], 3]", JsonArrayText.extract("prefix [1, [2], 3] suffix"))
    }

    @Test
    fun `isClosed returns false when no brackets exist`() {
        assertFalse(JsonArrayText.isClosed("hello world"))
    }

    @Test
    fun `isClosed returns false when only open bracket exists`() {
        assertFalse(JsonArrayText.isClosed("hello [ world"))
    }

    @Test
    fun `isClosed returns false when only close bracket exists`() {
        assertFalse(JsonArrayText.isClosed("hello ] world"))
    }

    @Test
    fun `isClosed returns false when close bracket is before open bracket`() {
        assertFalse(JsonArrayText.isClosed("] ["))
    }

    @Test
    fun `isClosed returns true for simple brackets`() {
        assertTrue(JsonArrayText.isClosed("[]"))
    }

    @Test
    fun `isClosed returns true for array with contents`() {
        assertTrue(JsonArrayText.isClosed("[1, 2, 3]"))
    }

    @Test
    fun `isClosed returns true ignoring prefix and suffix`() {
        assertTrue(JsonArrayText.isClosed("prefix\n[1, 2, 3]\nsuffix"))
    }
}
