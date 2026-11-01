package com.nitulshah.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A day with no scrolls and no unlocks wasn't tracked (accessibility service off).
 * Every component defaults to full credit on missing input, so such a day scores
 * 100 — hasData is what keeps that fake 100 out of history.
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
    fun `untracked day is a default 100 and is flagged as having no data`() {
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
