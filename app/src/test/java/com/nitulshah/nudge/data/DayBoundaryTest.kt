package com.nitulshah.nudge.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DayBoundary is the single source of "what day is it" for the whole app
 * (see its own doc comment); everything in this rework — CalibrationState,
 * DateRange, the repository backfill — is built on its key format and shift
 * arithmetic being internally consistent. It had no test coverage before this.
 */
class DayBoundaryTest {

    @Test
    fun `keyOf is stable Gregorian ASCII regardless of default locale`() {
        // A fixed instant: 2026-03-15T12:00:00Z-ish, locale-independent by contract.
        val millis = DayBoundary.parseOrNull("2026-03-15")!!.time
        assertEquals("2026-03-15", DayBoundary.keyOf(millis))
    }

    @Test
    fun `shift forward and backward are inverses`() {
        val date = "2026-01-31"
        assertEquals(date, DayBoundary.shift(DayBoundary.shift(date, 1), -1))
    }

    @Test
    fun `shift crosses month and year boundaries correctly`() {
        assertEquals("2026-03-01", DayBoundary.shift("2026-02-28", 1))
        assertEquals("2027-01-01", DayBoundary.shift("2026-12-31", 1))
    }

    @Test
    fun `daysAgo 0 is today and matches the today() key`() {
        assertEquals(DayBoundary.today(), DayBoundary.daysAgo(0))
    }

    @Test
    fun `shift on a malformed key returns it unchanged instead of throwing`() {
        assertEquals("not-a-date", DayBoundary.shift("not-a-date", 3))
    }
}
