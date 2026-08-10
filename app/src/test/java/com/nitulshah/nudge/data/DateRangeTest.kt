package com.nitulshah.nudge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DateRange.missingDates backs NudgeRepository.backfillMissingScrollDays and the
 * wellness backfill — the exact bug class this whole rework targets is "a day
 * silently absent from history forever," so these boundary cases matter.
 */
class DateRangeTest {

    @Test
    fun `no gaps returns an empty list`() {
        val existing = setOf("2026-01-01", "2026-01-02", "2026-01-03")
        val missing = DateRange.missingDates(existing, "2026-01-01", "2026-01-04")

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `nothing exists returns every date in the window`() {
        val missing = DateRange.missingDates(emptySet(), "2026-01-01", "2026-01-04")

        assertEquals(listOf("2026-01-01", "2026-01-02", "2026-01-03"), missing)
    }

    @Test
    fun `a gap in the middle is reported, ends of the window are not`() {
        val existing = setOf("2026-01-01", "2026-01-02", "2026-01-04", "2026-01-05")
        val missing = DateRange.missingDates(existing, "2026-01-01", "2026-01-06")

        assertEquals(listOf("2026-01-03"), missing)
    }

    @Test
    fun `endDateExclusive is not included even when missing`() {
        // Mirrors the real call sites always excluding "today" — its data isn't
        // final yet, so it must never be treated as a backfill target.
        val missing = DateRange.missingDates(setOf("2026-01-01"), "2026-01-01", "2026-01-02")

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `an empty or inverted window returns an empty list`() {
        assertTrue(DateRange.missingDates(emptySet(), "2026-01-05", "2026-01-05").isEmpty())
        assertTrue(DateRange.missingDates(emptySet(), "2026-01-05", "2026-01-01").isEmpty())
    }

    @Test
    fun `existing dates outside the window are irrelevant`() {
        val existing = setOf("2025-12-31", "2026-01-10")
        val missing = DateRange.missingDates(existing, "2026-01-01", "2026-01-03")

        assertEquals(listOf("2026-01-01", "2026-01-02"), missing)
    }
}
