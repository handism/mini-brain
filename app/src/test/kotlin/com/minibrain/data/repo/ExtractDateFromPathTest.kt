package com.minibrain.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractDateFromPathTest {

    private fun extract(path: String): String? =
        DocumentRepository.extractDateFromPath(path)

    // --- 完全日付（日まで揃う） ---

    @Test
    fun hyphenFullDate() {
        assertEquals("2024-12-15", extract("notes/2024-12-15.md"))
    }

    @Test
    fun slashFullDate() {
        assertEquals("2024-12-15", extract("notes/2024/12/15.md"))
    }

    @Test
    fun eightDigitFullDate() {
        assertEquals("2024-12-15", extract("notes/20241215.md"))
    }

    @Test
    fun underscoreFullDate() {
        assertEquals("2024-12-15", extract("notes/2024_12_15.md"))
    }

    @Test
    fun dotFullDate() {
        assertEquals("2024-12-15", extract("notes/2024.12.15.md"))
    }

    @Test
    fun jpFullDate() {
        assertEquals("2024-12-15", extract("日記/2024年12月15日_冬休み.md"))
    }

    // --- 月のみ ---

    @Test
    fun hyphenMonthOnly() {
        assertEquals("2024-12-01", extract("journal/2024-12.md"))
    }

    @Test
    fun jpMonthOnly() {
        assertEquals("2024-12-01", extract("日記/2024年12月.md"))
    }

    @Test
    fun sixDigitMonthOnly() {
        assertEquals("2024-12-01", extract("journal/202412.md"))
    }

    // --- 優先順位: 完全日付が月のみより優先される ---

    @Test
    fun fullDateBeatsMonthOnly() {
        // 完全日付パターンが先にマッチすべき。月のみで 2024-12-01 と誤抽出しないこと
        assertEquals("2024-12-15", extract("journal/2024-12-15.md"))
    }

    @Test
    fun fullDatePreferredFromMixedPath() {
        // 同じパスに月単位フォルダと日単位ファイル両方ある場合、日付付きが優先される
        assertEquals("2024-12-15", extract("journal/2024-12/2024-12-15.md"))
    }

    // --- 無効値の弾き ---

    @Test
    fun invalidDateReturnsNull() {
        assertNull(extract("invalid/9999-99-99.md"))
    }

    @Test
    fun outOfRangeYearReturnsNull() {
        assertNull(extract("notes/1850-01-01.md"))
    }

    @Test
    fun noDateReturnsNull() {
        assertNull(extract("notes/just-a-title.md"))
    }

    @Test
    fun phoneNumberLikeDoesNotMatch() {
        // 4桁-4桁（電話番号風）は月のみパターンの (?!\d) で弾かれる
        assertNull(extract("contacts/1234-5678.md"))
    }

    @Test
    fun invalidMonthFallsThroughToNull() {
        // 13月は LocalDate.of で失敗 → 後続パターンも失敗 → null
        assertNull(extract("notes/2024-13-50.md"))
    }

    @Test
    fun invalidDayFallsThroughToNull() {
        // 2月30日は LocalDate.of で DateTimeException 失敗 → 後続パターンも失敗 → null
        assertNull(extract("notes/2024-02-30.md"))
    }
}
