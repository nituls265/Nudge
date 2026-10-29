package com.nitulshah.nudge

import android.content.Context
import android.content.SharedPreferences
import com.nitulshah.nudge.data.CalibrationState

/**
 * The only places that need to agree on where the first-launch timestamp
 * lives: [MainActivity] (writer, once per install) and [readCalibrationState]
 * (reader, everywhere else). It has its own file so Android Auto Backup can
 * restore it on reinstall (see res/xml/backup_rules.xml) without also
 * restoring the app's other, unrelated state in "NudgePrefs".
 */
object CalibrationPrefs {
    const val FILE_NAME = "NudgeCalibration"
    const val LEGACY_FILE_NAME = "NudgePrefs"
    const val KEY_FIRST_LAUNCH_DATE = "FIRST_LAUNCH_DATE"
}

/** Calibration prefs, first copying the date over from where versions up to 1.8 kept it. */
fun calibrationPrefs(context: Context): SharedPreferences {
    val prefs = context.getSharedPreferences(CalibrationPrefs.FILE_NAME, Context.MODE_PRIVATE)
    if (!prefs.contains(CalibrationPrefs.KEY_FIRST_LAUNCH_DATE)) {
        val legacy = context.getSharedPreferences(CalibrationPrefs.LEGACY_FILE_NAME, Context.MODE_PRIVATE)
        if (legacy.contains(CalibrationPrefs.KEY_FIRST_LAUNCH_DATE)) {
            prefs.edit()
                .putLong(CalibrationPrefs.KEY_FIRST_LAUNCH_DATE, legacy.getLong(CalibrationPrefs.KEY_FIRST_LAUNCH_DATE, 0L))
                .apply()
        }
    }
    return prefs
}

/** Reads the persisted first-launch timestamp and computes [CalibrationState] from it. */
fun readCalibrationState(
    context: Context,
    nowMs: Long = System.currentTimeMillis()
): CalibrationState {
    val prefs = calibrationPrefs(context)
    val firstLaunchMs = prefs.getLong(CalibrationPrefs.KEY_FIRST_LAUNCH_DATE, nowMs)
    return CalibrationState.compute(firstLaunchMs, nowMs)
}
