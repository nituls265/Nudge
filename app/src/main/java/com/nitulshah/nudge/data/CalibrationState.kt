package com.nitulshah.nudge.data

import java.util.concurrent.TimeUnit

/**
 * The app's onboarding gate: for [TOTAL_DAYS] days after first launch, Nudge
 * shows a calibration ring instead of a wellness score, because a score
 * computed from less than a week of habits is misleading.
 *
 * This is intentionally the ONLY signal that gates that UI. It is a pure
 * function of two timestamps, so it is monotonic and always reaches
 * `isCalibrating == false` exactly [TOTAL_DAYS] days after install — it
 * cannot re-trigger, get stuck, or depend on background work having run.
 *
 * A missing day of usage history is a data-completeness problem, not an
 * onboarding problem, and must never re-open this gate — see
 * NudgeRepository.backfillMissingScrollDays for how that's handled instead
 * (self-heal the data, don't re-lock the UI).
 */
data class CalibrationState(
    val isCalibrating: Boolean,
    val daysRemaining: Int,
    val totalDays: Int = TOTAL_DAYS
) {
    companion object {
        const val TOTAL_DAYS = 7

        /**
         * @param firstLaunchMs epoch-millis of the recorded first launch.
         * @param nowMs epoch-millis of "now" — a parameter (not read internally)
         *   so this stays a pure function callers can unit-test without mocking
         *   the system clock.
         */
        fun compute(firstLaunchMs: Long, nowMs: Long): CalibrationState {
            val elapsedDays = TimeUnit.MILLISECONDS.toDays(nowMs - firstLaunchMs).toInt()
            // coerceIn also absorbs clock skew (nowMs < firstLaunchMs, e.g. a
            // restored backup or a device clock rolled back) as "day zero"
            // rather than a negative or out-of-range remaining count.
            val remaining = (TOTAL_DAYS - elapsedDays).coerceIn(0, TOTAL_DAYS)
            return CalibrationState(isCalibrating = remaining > 0, daysRemaining = remaining)
        }
    }
}
