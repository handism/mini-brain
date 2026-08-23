package com.minibrain.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DateResolverTest {

    // 固定基準日: 2025-06-10（火曜日）
    private val today = LocalDate.of(2025, 6, 10)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ─────────────── 曜日 ───────────────

    @Test
    fun `last week monday`() {
        // 先週月曜 = 2025-06-02
        val result = DateResolver.resolveToDateStrings("先週の月曜", today)
        assertEquals(listOf("2025-06-02"), result)
    }

    @Test
    fun `last week friday`() {
        // 先週金曜 = 2025-06-06
        val result = DateResolver.resolveToDateStrings("先週の金曜日", today)
        assertEquals(listOf("2025-06-06"), result)
    }

    @Test
    fun `last week sunday`() {
        // 先週日曜 = 2025-06-08
        val result = DateResolver.resolveToDateStrings("先週の日曜", today)
        assertEquals(listOf("2025-06-08"), result)
    }

    @Test
    fun `this week monday - past, returned`() {
        // 今週月曜 = 2025-06-09 は today(火曜)以前なので返す
        val result = DateResolver.resolveToDateStrings("今週の月曜", today)
        assertEquals(listOf("2025-06-09"), result)
    }

    @Test
    fun `this week tuesday - today, returned`() {
        // 今週火曜 = today なので返す
        val result = DateResolver.resolveToDateStrings("今週の火曜日", today)
        assertEquals(listOf("2025-06-10"), result)
    }

    @Test
    fun `this week wednesday - future, falls through to week range`() {
        // 水曜(2025-06-11)は未来のため今週指定の曜日ブランチをスキップ
        // "今週"キーワードにフォールバックして今週月〜今日を返す
        val result = DateResolver.resolveToDateStrings("今週の水曜", today)
        // 今週(月〜火) = [2025-06-10, 2025-06-09] の2件
        assertEquals(2, result.size)
        assertTrue(result.contains("2025-06-09"))
        assertTrue(result.contains("2025-06-10"))
    }

    // ─────────────── クォーター ───────────────

    @Test
    fun `Q1 of current year returns full quarter`() {
        val range = DateResolver.resolveDateRange("Q1", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2025, 1, 1), range!!.start)
        assertEquals(LocalDate.of(2025, 3, 31), range.end)
    }

    @Test
    fun `Q2 of current year ends at today`() {
        // Q2 は 4/1〜6/30 だが today が 6/10 なので end = today
        val range = DateResolver.resolveDateRange("Q2", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2025, 4, 1), range!!.start)
        assertEquals(today, range.end)
    }

    @Test
    fun `Q3 of last year returns full quarter`() {
        val range = DateResolver.resolveDateRange("去年のQ3", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2024, 7, 1), range!!.start)
        assertEquals(LocalDate.of(2024, 9, 30), range.end)
    }

    @Test
    fun `第2四半期 resolves same as Q2`() {
        val range = DateResolver.resolveDateRange("第2四半期", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2025, 4, 1), range!!.start)
    }

    @Test
    fun `year qualified quarter 2023Q2 returns full quarter`() {
        val range = DateResolver.resolveDateRange("2023年Q2", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2023, 4, 1), range!!.start)
        assertEquals(LocalDate.of(2023, 6, 30), range.end)
    }

    @Test
    fun `今年の第1四半期 resolves to 2025 Q1`() {
        val range = DateResolver.resolveDateRange("今年の第1四半期", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2025, 1, 1), range!!.start)
        assertEquals(LocalDate.of(2025, 3, 31), range.end)
    }

    // ─────────────── 元号 ───────────────

    @Test
    fun `reiwa 6 resolves to 2024`() {
        val range = DateResolver.resolveDateRange("令和6年", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2024, 1, 1), range!!.start)
        assertEquals(LocalDate.of(2024, 12, 31), range.end)
    }

    @Test
    fun `reiwa 6 march resolves to 2024-03`() {
        // 令和6年 = 2018+6 = 2024
        val range = DateResolver.resolveDateRange("令和6年3月のメモ", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2024, 3, 1), range!!.start)
        assertEquals(LocalDate.of(2024, 3, 31), range.end)
    }

    @Test
    fun `heisei 30 resolves to 2018`() {
        val range = DateResolver.resolveDateRange("平成30年", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2018, 1, 1), range!!.start)
        assertEquals(LocalDate.of(2018, 12, 31), range.end)
    }

    @Test
    fun `showa 60 resolves to 1985`() {
        val range = DateResolver.resolveDateRange("昭和60年", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(1985, 1, 1), range!!.start)
        assertEquals(LocalDate.of(1985, 12, 31), range.end)
    }

    @Test
    fun `taisho 10 resolves to 1921`() {
        val range = DateResolver.resolveDateRange("大正10年", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(1921, 1, 1), range!!.start)
    }

    @Test
    fun `R6 abbreviation resolves to reiwa 6`() {
        val range = DateResolver.resolveDateRange("R6年の記録", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2024, 1, 1), range!!.start)
    }

    @Test
    fun `invalid era month 13 returns null`() {
        val range = DateResolver.resolveDateRange("令和6年13月", today)
        assertNull(range)
    }

    @Test
    fun `invalid era month 0 returns null`() {
        val range = DateResolver.resolveDateRange("令和6年0月", today)
        assertNull(range)
    }

    // ─────────────── ドット/スラッシュ日付 ───────────────

    @Test
    fun `dot date 2024_03_01`() {
        val result = DateResolver.resolveToDateStrings("2024.03.01のメモ", today)
        assertEquals(listOf("2024-03-01"), result)
    }

    @Test
    fun `slash date 2024_03_01`() {
        val result = DateResolver.resolveToDateStrings("2024/03/01のメモ", today)
        assertEquals(listOf("2024-03-01"), result)
    }

    @Test
    fun `invalid dot date returns empty list`() {
        // 月13は無効
        val result = DateResolver.resolveToDateStrings("2024.13.01", today)
        assertEquals(emptyList<String>(), result)
    }

    // ─────────────── 既存機能の回帰テスト ───────────────

    @Test
    fun `yesterday`() {
        val result = DateResolver.resolveToDateStrings("昨日のこと", today)
        assertEquals(listOf("2025-06-09"), result)
    }

    @Test
    fun `day before yesterday`() {
        val result = DateResolver.resolveToDateStrings("一昨日", today)
        assertEquals(listOf("2025-06-08"), result)
    }

    @Test
    fun `today keyword`() {
        val result = DateResolver.resolveToDateStrings("今日", today)
        assertEquals(listOf("2025-06-10"), result)
    }

    @Test
    fun `3 days ago`() {
        val result = DateResolver.resolveToDateStrings("3日前のメモ", today)
        assertEquals(listOf("2025-06-07"), result)
    }

    @Test
    fun `last week range has 7 dates`() {
        val result = DateResolver.resolveToDateStrings("先週のまとめ", today)
        assertEquals(7, result.size)
    }

    @Test
    fun `last month returns full month`() {
        val result = DateResolver.resolveToDateStrings("先月", today)
        assertEquals(31, result.size) // May has 31 days
        assertTrue(result.contains("2025-05-01"))
        assertTrue(result.contains("2025-05-31"))
    }

    @Test
    fun `resolveDateRange last year`() {
        val range = DateResolver.resolveDateRange("去年", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2024, 1, 1), range!!.start)
        assertEquals(LocalDate.of(2024, 12, 31), range.end)
    }

    @Test
    fun `resolveDateRange this year ends at today`() {
        val range = DateResolver.resolveDateRange("今年", today)
        assertNotNull(range)
        assertEquals(LocalDate.of(2025, 1, 1), range!!.start)
        assertEquals(today, range.end)
    }

    @Test
    fun `resolveDateRange unknown expression returns null`() {
        val range = DateResolver.resolveDateRange("ランダムな文章", today)
        assertNull(range)
    }

    @Test
    fun `isDiaryQuery detects weekday pattern`() {
        assertTrue(DateResolver.isDiaryQuery("先週の月曜日に何した？"))
    }

    @Test
    fun `isDiaryQuery detects era`() {
        assertTrue(DateResolver.isDiaryQuery("令和6年のこと"))
        assertTrue(DateResolver.isDiaryQuery("平成の思い出"))
    }

    @Test
    fun `isDiaryQuery detects quarter`() {
        assertTrue(DateResolver.isDiaryQuery("Q2の振り返り"))
        assertTrue(DateResolver.isDiaryQuery("第3四半期のレポート"))
    }

    @Test
    fun `isDiaryQuery detects dot date`() {
        assertTrue(DateResolver.isDiaryQuery("2024.05.01のメモ"))
    }

    // ─────────────── isDateQuery ───────────────

    @Test
    fun `isDateQuery detects time asking questions`() {
        assertTrue(DateResolver.isDateQuery("いつ行ったの？"))
        assertTrue(DateResolver.isDateQuery("何月にあった？"))
        assertTrue(DateResolver.isDateQuery("何日だった？"))
        assertTrue(DateResolver.isDateQuery("何年だった？"))
        assertTrue(DateResolver.isDateQuery("何年前？"))
        assertTrue(DateResolver.isDateQuery("数ヶ月前"))
        assertTrue(DateResolver.isDateQuery("去年の話"))
        assertTrue(DateResolver.isDateQuery("先月は何した？"))
        assertTrue(DateResolver.isDateQuery("先週の出来事"))
        assertTrue(DateResolver.isDateQuery("いつから始めた？"))
        assertTrue(DateResolver.isDateQuery("いつまでかかる？"))
    }

    @Test
    fun `isDateQuery returns false for unrelated queries`() {
        assertFalse(DateResolver.isDateQuery("誰と行った？"))
        assertFalse(DateResolver.isDateQuery("どこにある？"))
        assertFalse(DateResolver.isDateQuery("何が起こった？"))
        assertFalse(DateResolver.isDateQuery("今日のこと"))
    }
}
