package com.minibrain.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber

@RunWith(RobolectricTestRunner::class)
class JsonArraysTest {

    private val dummyTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // No-op
        }
    }

    @Before
    fun setUp() {
        Timber.plant(dummyTree)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `toStringList returns empty list for null input`() {
        val result = JsonArrays.toStringList(null)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `toStringList returns empty list for empty string`() {
        val result = JsonArrays.toStringList("")
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `toStringList returns empty list for blank string`() {
        val result = JsonArrays.toStringList("   ")
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `toStringList parses valid JSON array of strings`() {
        val json = """["apple", "banana", "cherry"]"""
        val result = JsonArrays.toStringList(json)
        assertEquals(listOf("apple", "banana", "cherry"), result)
    }

    @Test
    fun `toStringList returns empty list for invalid JSON`() {
        val json = """["apple", "banana"""" // missing closing bracket
        val result = JsonArrays.toStringList(json)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `toStringList returns empty list for non-array JSON`() {
        val json = """{"key": "value"}"""
        val result = JsonArrays.toStringList(json)
        assertEquals(emptyList<String>(), result)
    }
}
