package com.nitulshah.nudge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * CalibrationState.compute is the entire onboarding gate — the bug this guards
 * against (calibration mode never closing, even long after install) was caused
 * by a second, data-dependent signal being allowed to hold the gate open. These
 * tests exist to keep that gate a pure, monotonic function of time: given a
 * fixed install timestamp, it must count down to zero once and never re-open.
 */
class CalibrationStateTest {

    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun `day zero — just installed — has the full window remaining`() {
        val firstLaunch = 0L
        val state = CalibrationState.compute(firstLaunch, nowMs = firstLaunch)

        assertTrue(state.isCalibrating)
        assertEquals(7, state.daysRemaining)
        assertEquals(7, state.totalDays)
    }

    @Test
    fun `midway through the window still calibrating with the correct remainder`() {
        val firstLaunch = 0L
        val state = CalibrationState.compute(firstLaunch, nowMs = firstLaunch + 3 * day)

        assertTrue(state.isCalibrating)
        assertEquals(4, state.daysRemaining)
    }

    @Test
    fun `exactly one full week later calibration has ended`() {
        val firstLaunch = 0L
        val state = CalibrationState.compute(firstLaunch, nowMs = firstLaunch + 7 * day)

        assertFalse(state.isCalibrating)
        assertEquals(0, state.daysRemaining)
    }

    @Test
    fun `long after install remains ended, not negative`() {
        val firstLaunch = 0L
        val state = CalibrationState.compute(firstLaunch, nowMs = firstLaunch + 90 * day)

        assertFalse(state.isCalibrating)
        assertEquals(0, state.daysRemaining)
    }

    @Test
    fun `just under a full day still counts as day zero (integer day truncation)`() {
        val firstLaunch = 0L
        val state = CalibrationState.compute(firstLaunch, nowMs = firstLaunch + day - 1)

        assertTrue(state.isCalibrating)
        assertEquals(7, state.daysRemaining)
    }

    @Test
    fun `clock skew before install date is clamped to the start of the window, not negative or crashing`() {
        // A restored backup or a device clock rolled back could make "now" appear
        // to be before the recorded first-launch timestamp.
        val firstLaunch = 10 * day
        val state = CalibrationState.compute(firstLaunch, nowMs = 0L)

        assertTrue(state.isCalibrating)
        assertEquals(7, state.daysRemaining)
    }

    @Test
    fun `does not depend on wall-clock time — same inputs always produce the same output`() {
        val firstLaunch = 1_700_000_000_000L
        val now = firstLaunch + 2 * day
        val a = CalibrationState.compute(firstLaunch, now)
        val b = CalibrationState.compute(firstLaunch, now)

        assertEquals(a, b)
    }
}
