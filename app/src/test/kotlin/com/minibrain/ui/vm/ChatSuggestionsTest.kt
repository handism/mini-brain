package com.minibrain.ui.vm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ChatSuggestionsTest {

    private val texts = SuggestionTexts(
        lastMonth = "先月",
        thisMonth = "今月",
        topic = { "「$it」" },
        fallback = listOf("固定1", "固定2"),
    )
    private val today = LocalDate.of(2026, 10, 5)

    @Test
    fun `empty knowledge base falls back to fixed suggestions`() {
        assertEquals(listOf("固定1", "固定2"), buildChatSuggestions(emptyList(), emptyList(), today, texts))
    }

    @Test
    fun `last month documents and topics come first`() {
        val result = buildChatSuggestions(
            documentDates = listOf("2026-09-12", "2026-10-01"),
            recentFileNames = listOf("2026-10-01.md", "サウナしきじ.md", "胃.md", "読書メモ.md"),
            today = today,
            texts = texts,
        )
        // 日付だけのファイル名と 1 文字の stem は話題にしない。話題は 2 件まで
        assertEquals(listOf("先月", "「サウナしきじ」", "「読書メモ」", "固定1"), result)
    }

    @Test
    fun `this month is used when last month has no documents`() {
        val result = buildChatSuggestions(listOf("2026-10-03", "bad-date"), emptyList(), today, texts)
        assertEquals(listOf("今月", "固定1"), result)
    }

    @Test
    fun `january looks back to december of previous year`() {
        val result = buildChatSuggestions(listOf("2025-12-31"), emptyList(), LocalDate.of(2026, 1, 2), texts)
        assertEquals(listOf("先月", "固定1"), result)
    }
}
