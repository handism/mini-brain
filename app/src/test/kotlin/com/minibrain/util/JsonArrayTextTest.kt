package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonArrayTextTest {

    @Test
    fun `extract returns null when no brackets are present`() {
        assertNull(JsonArrayText.extract("hello world"))
    }

    @Test
    fun `extract returns null when opening bracket is missing`() {
        assertNull(JsonArrayText.extract("hello world]"))
    }

    @Test
    fun `extract returns null when closing bracket is missing`() {
        assertNull(JsonArrayText.extract("[hello world"))
    }

    @Test
    fun `extract returns null when brackets are in wrong order`() {
        assertNull(JsonArrayText.extract("]hello world["))
    }

    @Test
    fun `extract returns exact match for valid json array`() {
        val json = """["a", "b", "c"]"""
        assertEquals(json, JsonArrayText.extract(json))
    }

    @Test
    fun `extract ignores text before and after array`() {
        val raw = """
            Here is the JSON:
            ```json
            ["item1", "item2"]
            ```
            Hope this helps!
        """.trimIndent()
        assertEquals("""["item1", "item2"]""", JsonArrayText.extract(raw))
    }

    @Test
    fun `extract handles nested arrays by taking first open and last close`() {
        val raw = """prefix [["nested1"], ["nested2"]] suffix"""
        assertEquals("""[["nested1"], ["nested2"]]""", JsonArrayText.extract(raw))
    }

    @Test
    fun `extract returns empty array if brackets are adjacent`() {
        assertEquals("[]", JsonArrayText.extract("prefix [] suffix"))
    }
}
