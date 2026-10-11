package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun testStem_withPathAndExtension() {
        assertEquals("スパイス堂", FileNames.stem("notes/2021-03-15/スパイス堂.md"))
    }

    @Test
    fun testStem_withNoPathAndExtension() {
        assertEquals("スパイス堂", FileNames.stem("スパイス堂.md"))
    }

    @Test
    fun testStem_withUppercaseExtension() {
        assertEquals("スパイス堂", FileNames.stem("notes/スパイス堂.MD"))
    }

    @Test
    fun testStem_withNoExtension() {
        assertEquals("スパイス堂", FileNames.stem("notes/スパイス堂"))
    }

    @Test
    fun testStem_withOtherExtension() {
        assertEquals("スパイス堂.txt", FileNames.stem("notes/スパイス堂.txt"))
    }

    @Test
    fun testStemMatchesAnyQuery_match() {
        assertTrue(FileNames.stemMatchesAnyQuery("notes/スパイス堂.md", listOf("スパイス堂のレシピ", "カレー")))
    }

    @Test
    fun testStemMatchesAnyQuery_ignoreCase() {
        assertTrue(FileNames.stemMatchesAnyQuery("notes/AI.md", listOf("aiの話")))
    }

    @Test
    fun testStemMatchesAnyQuery_noMatch() {
        assertFalse(FileNames.stemMatchesAnyQuery("notes/スパイス堂.md", listOf("カレー", "ラーメン")))
    }

    @Test
    fun testStemMatchesAnyQuery_stemTooShort() {
        assertFalse(FileNames.stemMatchesAnyQuery("notes/.md", listOf("カレー")))
    }
}
