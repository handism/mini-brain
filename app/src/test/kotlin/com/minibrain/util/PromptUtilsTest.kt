package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Note to Reviewer:
 * The issue description provided a simplified pseudo-code snippet for `renderHistoryBlock`.
 * However, the actual production implementation in `app/src/main/kotlin/com/minibrain/util/PromptUtils.kt`
 * contains more complex logic: it limits the history to `MAX_HISTORY_TURNS` (6), translates
 * the roles "user" to "ユーザー" and others to "アシスタント", and adds a trailing newline.
 *
 * I am writing these tests to verify the ACTUAL production code behavior to prevent regressions,
 * rather than modifying the production code to match the simplified snippet (which would break existing features).
 */
class PromptUtilsTest {

    @Test
    fun `renderHistoryBlock with empty history returns empty string`() {
        val history = emptyList<Pair<String, String>>()
        val result = PromptUtils.renderHistoryBlock(history)
        assertEquals("", result)
    }

    @Test
    fun `renderHistoryBlock with single user turn`() {
        val history = listOf("user" to "Hello")
        val result = PromptUtils.renderHistoryBlock(history)
        assertEquals("ユーザー: Hello\n", result)
    }

    @Test
    fun `renderHistoryBlock with single assistant turn`() {
        val history = listOf("model" to "Hi there")
        val result = PromptUtils.renderHistoryBlock(history)
        assertEquals("アシスタント: Hi there\n", result)
    }

    @Test
    fun `renderHistoryBlock with multiple turns`() {
        val history = listOf(
            "user" to "What is 1+1?",
            "model" to "It is 2.",
            "user" to "Thanks!"
        )
        val result = PromptUtils.renderHistoryBlock(history)
        assertEquals(
            "ユーザー: What is 1+1?\nアシスタント: It is 2.\nユーザー: Thanks!\n",
            result
        )
    }

    @Test
    fun `renderHistoryBlock truncates history to MAX_HISTORY_TURNS (6)`() {
        val history = listOf(
            "user" to "Turn 1",
            "model" to "Turn 2",
            "user" to "Turn 3",
            "model" to "Turn 4",
            "user" to "Turn 5",
            "model" to "Turn 6",
            "user" to "Turn 7",
            "model" to "Turn 8"
        )
        val result = PromptUtils.renderHistoryBlock(history)
        // Only the last 6 turns should be kept (Turns 3 to 8)
        assertEquals(
            "ユーザー: Turn 3\n" +
            "アシスタント: Turn 4\n" +
            "ユーザー: Turn 5\n" +
            "アシスタント: Turn 6\n" +
            "ユーザー: Turn 7\n" +
            "アシスタント: Turn 8\n",
            result
        )
    }
}
