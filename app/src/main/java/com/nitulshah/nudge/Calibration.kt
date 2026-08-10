package com.nitulshah.nudge

import android.content.Context
import com.nitulshah.nudge.data.CalibrationState

/**
 * The only two places that need to agree on where the first-launch timestamp
 * lives: [MainActivity] (writer, once per install) and [readCalibrationState]
 * (reader, everywhere else). Scoped to calibration only — the app's other
 * SharedPreferences keys live in "NudgePrefs" too but are unrelated state
 * (live scroll/unlock counters, sync code) with their own lifecycles, so they
 * intentionally aren't folded into this object.
 */
object CalibrationPrefs {
    const val FILE_NAME = "NudgePrefs"
    const val KEY_FIRST_LAUNCH_DATE = "FIRST_LAUNCH_DATE"
}

/** Reads the persisted first-launch timestamp and computes [CalibrationState] from it. */
fun readCalibrationState(
    context: Context,
    nowMs: Long = System.currentTimeMillis()
): CalibrationState {
    val prefs = context.getSharedPreferences(CalibrationPrefs.FILE_NAME, Context.MODE_PRIVATE)
    val firstLaunchMs = prefs.getLong(CalibrationPrefs.KEY_FIRST_LAUNCH_DATE, nowMs)
    return CalibrationState.compute(firstLaunchMs, nowMs)
}
