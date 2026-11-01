package com.nitulshah.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A day with no scrolls and no unlocks scores 100 (every component gives full
 * credit on missing input). That's right once tracking has started (the phone
 * wasn't used), so hasData only reports whether anything was recorded; the
 * view model decides whether the day is scored at all.
 */
class WellnessCalculatorTest {

    private fun score(scrolls: Int, unlocks: Int) = WellnessCalculator.calculate(
        todayScrolls      = scrolls,
        sevenDayAvg       = 0f,
        unlockCount       = unlocks,
        avgSessionMin     = 0f,
        longestSessionMin = 0,
        firstUnlockMs     = 0L,
        lastUnlockMs      = 0L,
        topApps           = emptyList()
    )

    @Test
    fun `a day with nothing recorded scores 100 and reports no data`() {
        val s = score(scrolls = 0, unlocks = 0)
        assertEquals(100, s.total)
        assertFalse(s.hasData)
    }

    @Test
    fun `any scroll or unlock counts as tracked`() {
        assertTrue(score(scrolls = 1, unlocks = 0).hasData)
        assertTrue(score(scrolls = 0, unlocks = 1).hasData)
    }
}
