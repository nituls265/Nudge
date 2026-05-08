package com.example.nudgev0

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.nudgev0.data.AppScrollDay
import com.example.nudgev0.data.ScrollDatabase
import com.example.nudgev0.data.ScrollDay
import com.example.nudgev0.data.UnlockDay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ResetWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val db    = ScrollDatabase.getDatabase(applicationContext)
        val prefs = applicationContext.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)

        val sdf       = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val yesterday = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)

        return try {
            // ── Scroll: DB is source of truth (saved every 2s all day) ────────
            // SharedPrefs may already hold today's count due to service rollover
            // race; always trust the DB entry for yesterday.
            val dbScrollDay = db.scrollDao().getDay(yesterday)
            val scrollCount = dbScrollDay?.count
                ?: prefs.getInt("CURRENT_SCROLL_COUNT", 0)
            if (dbScrollDay == null && scrollCount > 0) {
                db.scrollDao().insertOrUpdate(ScrollDay(yesterday, scrollCount))
            }
            AnalyticsHelper.logDailySummary(scrollCount)
            prefs.edit()
                .putInt("CURRENT_SCROLL_COUNT", 0)
                .putString("LAST_SCROLL_DATE", yesterday)
                .apply()
            MyAccessibilityService.resetScrollCount()

            // ── Unlock: DB is source of truth ─────────────────────────────────
            val dbUnlockDay = db.unlockDao().getDay(yesterday)
            if (dbUnlockDay == null) {
                // No DB entry yet — fall back to SharedPrefs
                db.unlockDao().insertOrUpdate(
                    UnlockDay(
                        date              = yesterday,
                        count             = prefs.getInt("CURRENT_UNLOCK_COUNT", 0),
                        firstUnlockMs     = prefs.getLong("TODAY_FIRST_UNLOCK_MS", 0L),
                        lastUnlockMs      = prefs.getLong("TODAY_LAST_UNLOCK_MS", 0L),
                        avgSessionMin     = prefs.getFloat("TODAY_AVG_SESSION_MIN", 0f),
                        longestSessionMin = prefs.getInt("TODAY_LONGEST_SESSION_MIN", 0)
                    )
                )
            }
            prefs.edit()
                .putInt("CURRENT_UNLOCK_COUNT", 0)
                .putLong("SESSION_START_MS", 0L)
                .putLong("TODAY_FIRST_UNLOCK_MS", 0L)
                .putLong("TODAY_LAST_UNLOCK_MS", 0L)
                .putLong("TODAY_TOTAL_SESSION_MS", 0L)
                .putInt("TODAY_COMPLETED_SESSIONS", 0)
                .putFloat("TODAY_AVG_SESSION_MIN", 0f)
                .putInt("TODAY_LONGEST_SESSION_MIN", 0)
                .apply()
            MyAccessibilityService.resetUnlockCount()

            // ── App scrolls: DB already has per-app data; just clear live state ─
            prefs.edit().putString("APP_SCROLL_COUNTS", "{}").apply()
            MyAccessibilityService._appScrollCounts.value = emptyMap()

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
