package com.nitulshah.nudge

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nitulshah.nudge.data.DayBoundary
import com.nitulshah.nudge.data.NudgeRepository
import com.nitulshah.nudge.data.ScrollDay
import com.nitulshah.nudge.data.UnlockDay
import com.nitulshah.nudge.data.WellnessDay
import kotlinx.coroutines.flow.firstOrNull

class ResetWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val repo  = NudgeRepository.get(applicationContext)
        val prefs = applicationContext.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)

        val today     = DayBoundary.today()
        val yesterday = DayBoundary.daysAgo(1)

        return try {
            // ── Scroll: DB is source of truth ─────────────────────────────────
            val dbScrollDay = repo.scrollDay(yesterday)
            val scrollCount = dbScrollDay?.count
                ?: prefs.getInt("CURRENT_SCROLL_COUNT", 0)
            if (dbScrollDay == null) {
                repo.upsertScrollDay(ScrollDay(yesterday, scrollCount))
            }
            AnalyticsHelper.logDailySummary(scrollCount)

            // Only reset live scroll count if the user hasn't already scrolled today.
            // If ResetWorker fires late (Doze delay) and the user has been scrolling
            // since midnight, LAST_SCROLL_DATE will already equal today — don't wipe it.
            val lastScrollDate = prefs.getString("LAST_SCROLL_DATE", "") ?: ""
            val userAlreadyScrolledToday = lastScrollDate == today
            if (!userAlreadyScrolledToday) {
                prefs.edit()
                    .putInt("CURRENT_SCROLL_COUNT", 0)
                    .putString("LAST_SCROLL_DATE", yesterday)
                    .apply()
                MyAccessibilityService.resetScrollCount()
            }

            // ── Unlock: DB is source of truth ─────────────────────────────────
            val dbUnlockDay = repo.unlockDay(yesterday)
            val unlockCount    = dbUnlockDay?.count             ?: prefs.getInt("CURRENT_UNLOCK_COUNT", 0)
            val firstUnlockMs  = dbUnlockDay?.firstUnlockMs     ?: prefs.getLong("TODAY_FIRST_UNLOCK_MS", 0L)
            val lastUnlockMs   = dbUnlockDay?.lastUnlockMs      ?: prefs.getLong("TODAY_LAST_UNLOCK_MS", 0L)
            val avgSessionMin  = dbUnlockDay?.avgSessionMin     ?: prefs.getFloat("TODAY_AVG_SESSION_MIN", 0f)
            val longestSession = dbUnlockDay?.longestSessionMin ?: prefs.getInt("TODAY_LONGEST_SESSION_MIN", 0)

            if (dbUnlockDay == null) {
                repo.upsertUnlockDay(
                    UnlockDay(
                        date              = yesterday,
                        count             = unlockCount,
                        firstUnlockMs     = firstUnlockMs,
                        lastUnlockMs      = lastUnlockMs,
                        avgSessionMin     = avgSessionMin,
                        longestSessionMin = longestSession
                    )
                )
            }
            // Only wipe live unlock state if the user hasn't already unlocked today.
            // ResetWorker can be delayed by Doze/battery optimisation and fire well after
            // midnight. If the user unlocked at e.g. 12:30 AM, onPhoneUnlocked() already
            // stamped TODAY_FIRST_UNLOCK_MS with a today timestamp. Blindly clearing it
            // here would erase that unlock and make the next morning unlock appear "first".
            val existingFirstUnlockMs = prefs.getLong("TODAY_FIRST_UNLOCK_MS", 0L)
            val existingFirstUnlockDate = if (existingFirstUnlockMs > 0L)
                DayBoundary.keyOf(existingFirstUnlockMs) else ""
            val userAlreadyUnlockedToday = existingFirstUnlockDate == today

            if (userAlreadyUnlockedToday) {
                // Preserve today's unlock state — only clear session timing
                prefs.edit()
                    .putLong("SESSION_START_MS", 0L)
                    .apply()
                // Do NOT call resetUnlockCount() — in-memory state is already correct
            } else {
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
            }

            // ── App scrolls: DB already has per-app data; clear live state ─────
            prefs.edit().putString("APP_SCROLL_COUNTS", "{}").apply()
            MyAccessibilityService._appScrollCounts.value = emptyMap()

            // ── Wellness: compute final score for yesterday and persist ─────────
            if (repo.wellnessDay(yesterday) == null) {
                // Build top-apps from yesterday's DB app scroll data.
                // appTotalsBetween is a Flow — collect the first value via firstOrNull().
                val appTotals = repo.appTotalsBetween(yesterday, today)
                    .firstOrNull() ?: emptyList()

                val topApps = appTotals
                    .filter { !isSystemPackage(it.packageName) }
                    .sortedByDescending { it.total }
                    .map { it.packageName to it.total }

                // 7-day average: query 8 days back, exclude yesterday (which is "today" in this context)
                val eightDaysBack = DayBoundary.daysAgo(8)
                val sevenDayHistory = repo.scrollHistorySince(eightDaysBack)
                    .firstOrNull() ?: emptyList()
                val pastCounts: List<Int> = sevenDayHistory
                    .filter { it.date != yesterday }
                    .map { it.count }
                val sevenDayAvg: Float = if (pastCounts.isEmpty()) 0f
                                         else pastCounts.average().toFloat()

                // Day before yesterday's last unlock — the start of the overnight
                // gap that yesterday's morning-hygiene component measures.
                val dayBeforeYesterday = DayBoundary.daysAgo(2)
                val prevDayLastUnlockMs = repo.unlockDay(dayBeforeYesterday)?.lastUnlockMs ?: 0L

                // Personal first-unlock baseline: 14 days before yesterday, excluding
                // yesterday itself and any zero-unlock days.
                val baselineHistory = repo.unlockHistorySince(DayBoundary.daysAgo(15))
                    .firstOrNull() ?: emptyList()
                val pastFirstUnlocks = baselineHistory
                    .filter { it.date != yesterday && it.firstUnlockMs > 0L }
                    .map { it.firstUnlockMs }
                val avgFirstUnlockMinute = WellnessCalculator.averageFirstUnlockMinute(pastFirstUnlocks)

                val score = WellnessCalculator.calculate(
                    todayScrolls                  = scrollCount,
                    sevenDayAvg                   = sevenDayAvg,
                    unlockCount                   = unlockCount,
                    avgSessionMin                 = avgSessionMin,
                    longestSessionMin             = longestSession,
                    firstUnlockMs                 = firstUnlockMs,
                    lastUnlockMs                  = lastUnlockMs,
                    topApps                       = topApps,
                    previousDayLastUnlockMs       = prevDayLastUnlockMs,
                    personalAvgFirstUnlockMinute  = avgFirstUnlockMinute
                )
                repo.upsertWellnessDay(
                    WellnessDay(
                        date             = yesterday,
                        score            = score.total,
                        tier             = score.tier.name,
                        scrollVolume     = score.scrollVolume,
                        sessionBehaviour = score.sessionBehaviour,
                        unlockFrequency  = score.unlockFrequency,
                        timeHygiene      = score.timeHygiene,
                        appQuality       = score.appQuality,
                        bedtimeScore     = score.bedtimeScore,
                        gapScore         = score.gapScore,
                        consistencyScore = score.consistencyScore
                    )
                )
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == "unknown") return true
        val systemPrefixes = listOf("android", "com.android.", "com.google.android.", "com.samsung.android.")
        return systemPrefixes.any { pkg == it || pkg.startsWith(it) }
    }
}
