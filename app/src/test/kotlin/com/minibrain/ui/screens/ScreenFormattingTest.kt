package com.minibrain.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ScreenFormattingTest {

    private val zone = ZoneId.of("Asia/Tokyo")
    private val today = LocalDate.of(2026, 10, 5)

    private fun millis(dateTime: LocalDateTime) = dateTime.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `sessionTimeLabel shows time only for today and yesterday`() {
        assertEquals(
            SessionTimeLabel.Today("9:05"),
            sessionTimeLabel(millis(LocalDateTime.of(2026, 10, 5, 9, 5)), today, zone),
        )
        assertEquals(
            SessionTimeLabel.Yesterday("23:59"),
            sessionTimeLabel(millis(LocalDateTime.of(2026, 10, 4, 23, 59)), today, zone),
        )
    }

    @Test
    fun `sessionTimeLabel omits year within the same year`() {
        assertEquals(
            SessionTimeLabel.Date("1/2 13:00"),
            sessionTimeLabel(millis(LocalDateTime.of(2026, 1, 2, 13, 0)), today, zone),
        )
        assertEquals(
            SessionTimeLabel.Date("2025/12/31"),
            sessionTimeLabel(millis(LocalDateTime.of(2025, 12, 31, 13, 0)), today, zone),
        )
    }

    @Test
    fun `estimateRemainingMs waits for enough samples`() {
        assertNull(estimateRemainingMs(done = 2, remaining = 10, elapsedMs = 1_000))
        assertNull(estimateRemainingMs(done = 5, remaining = 0, elapsedMs = 1_000))
        assertEquals(20_000L, estimateRemainingMs(done = 5, remaining = 10, elapsedMs = 10_000))
    }
}
