package com.minibrain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DateValidatorTest {

    private val currentYear = LocalDate.now().year

    @Test
    fun `parseDay returns valid string for valid date`() {
        assertEquals("2023-05-15", DateValidator.parseDay("2023", "05", "15"))
        assertEquals("2023-05-15", DateValidator.parseDay("2023", "5", "15"))
    }

    @Test
    fun `parseDay returns null for out of range year`() {
        assertNull(DateValidator.parseDay("1989", "05", "15")) // Before 1990
        assertNull(DateValidator.parseDay("${currentYear + 1}", "05", "15")) // Future year beyond current year
    }

    @Test
    fun `parseDay returns null for invalid date`() {
        assertNull(DateValidator.parseDay("2023", "02", "30")) // Invalid day for Feb
        assertNull(DateValidator.parseDay("2023", "13", "15")) // Invalid month
        assertNull(DateValidator.parseDay("2023", "05", "abc")) // Not a number
    }

    @Test
    fun `parseMonth returns valid string for valid month`() {
        assertEquals("2023-05-01", DateValidator.parseMonth("2023", "05"))
        assertEquals("2023-05-01", DateValidator.parseMonth("2023", "5"))
    }

    @Test
    fun `parseMonth returns null for out of range year`() {
        assertNull(DateValidator.parseMonth("1989", "05"))
        assertNull(DateValidator.parseMonth("${currentYear + 1}", "05"))
    }

    @Test
    fun `parseMonth returns null for invalid month`() {
        assertNull(DateValidator.parseMonth("2023", "13"))
        assertNull(DateValidator.parseMonth("2023", "abc"))
    }

    @Test
    fun `parseDayNotInFuture returns valid string for past or present date`() {
        val today = LocalDate.of(2023, 5, 15)
        assertEquals("2023-05-14", DateValidator.parseDayNotInFuture("2023", "05", "14", today)) // Past
        assertEquals("2023-05-15", DateValidator.parseDayNotInFuture("2023", "05", "15", today)) // Present
    }

    @Test
    fun `parseDayNotInFuture returns null for future date`() {
        val today = LocalDate.of(2023, 5, 15)
        assertNull(DateValidator.parseDayNotInFuture("2023", "05", "16", today)) // Future day
        assertNull(DateValidator.parseDayNotInFuture("2023", "06", "15", today)) // Future month
    }

    @Test
    fun `parseDayNotInFuture returns null for invalid date`() {
        val today = LocalDate.of(2023, 5, 15)
        assertNull(DateValidator.parseDayNotInFuture("2023", "02", "30", today))
    }

    @Test
    fun `parseMonthNotInFuture returns valid string for past or present month`() {
        val today = LocalDate.of(2023, 5, 15)
        assertEquals("2023-04-01", DateValidator.parseMonthNotInFuture("2023", "04", today)) // Past month
        assertEquals("2023-05-01", DateValidator.parseMonthNotInFuture("2023", "05", today)) // Present month
    }

    @Test
    fun `parseMonthNotInFuture returns null for future month`() {
        val today = LocalDate.of(2023, 5, 15)
        assertNull(DateValidator.parseMonthNotInFuture("2023", "06", today)) // Future month
    }

    @Test
    fun `parseMonthNotInFuture returns null for invalid month`() {
        val today = LocalDate.of(2023, 5, 15)
        assertNull(DateValidator.parseMonthNotInFuture("2023", "13", today))
    }
}
