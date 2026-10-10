package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JsonArraysTest {
    @Test
    fun testNullOrBlank() {
        assertEquals(emptyList<String>(), JsonArrays.toStringList(null))
        assertEquals(emptyList<String>(), JsonArrays.toStringList(""))
        assertEquals(emptyList<String>(), JsonArrays.toStringList("   "))
    }

    @Test
    fun testValidJsonArray() {
        val json = """["a", "b", "c"]"""
        assertEquals(listOf("a", "b", "c"), JsonArrays.toStringList(json))
    }

    @Test
    fun testValidJsonArrayWithNumbers() {
        val json = """[1, 2, 3]"""
        // getString on numbers returns their string representation
        assertEquals(listOf("1", "2", "3"), JsonArrays.toStringList(json))
    }

    @Test
    fun testInvalidJsonFallback() {
        val json = """not json"""
        // Should catch exception and return emptyList
        assertEquals(emptyList<String>(), JsonArrays.toStringList(json))
    }

    @Test
    fun testJsonObjectInsteadOfArrayFallback() {
        val json = """{"a": "b"}"""
        // Should catch exception and return emptyList
        assertEquals(emptyList<String>(), JsonArrays.toStringList(json))
    }
}
