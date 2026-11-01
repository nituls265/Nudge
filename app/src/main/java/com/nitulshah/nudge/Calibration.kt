package com.nitulshah.nudge

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the first-launch (install) timestamp lives. [MainActivity] writes it
 * once per install; [ScrollViewModel] reads it so history backfill and wellness
 * scoring never invent data for days before install. (The names still say
 * "calibration" because versions up to 1.9 used this date to gate a 7-day
 * calibration period; the file name must stay as-is for Auto Backup restores.)
 *
 * It has its own file so Android Auto Backup can restore it on reinstall (see res/xml/backup_rules.xml) without also
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
