package com.nitulshah.nudge.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single coordination point for all persistence.
 *
 * Before this layer existed, several independent writers each reached into
 * `ScrollDatabase.getDatabase()` and wrote multiple tables with separate,
 * non-transactional inserts:
 *   • the accessibility service's debounced scroll save
 *   • the accessibility service's unlock save
 *   • the accessibility service's midnight-rollover block (3 separate inserts)
 *   • ResetWorker (midnight summary + wellness)
 *   • the ViewModel's wellness backfill
 *
 * A crash or race *between* those inserts could leave a day partially written
 * (e.g. the daily total saved but the per-app rows lost). NudgeRepository:
 *   • owns the single Room database + its DAOs
 *   • exposes typed read Flows / suspend reads
 *   • wraps every multi-table write in a transaction, so a day is all-or-nothing
 *
 * It is a process-wide singleton (the underlying Room DB already is), so the
 * service, the worker, and the ViewModel all share one instance and one
 * serialized write path.
 */
class NudgeRepository private constructor(private val db: ScrollDatabase) {

    private val scrollDao    = db.scrollDao()
    private val unlockDao    = db.unlockDao()
    private val appScrollDao = db.appScrollDao()
    private val wellnessDao  = db.wellnessDao()

    // ── Scroll reads ────────────────────────────────────────────────────────────
    fun scrollHistorySince(startDate: String): Flow<List<ScrollDay>> =
        scrollDao.getHistorySince(startDate)
    suspend fun scrollDay(date: String): ScrollDay? = scrollDao.getDay(date)
    suspend fun scrollHoursForDate(date: String): List<ScrollHour> =
        scrollDao.getHoursForDate(date)
    fun scrollPeakHourSince(startDate: String): Flow<HourTotal?> =
        scrollDao.getPeakHourSince(startDate)
    suspend fun scrollPeakHourForDate(date: String): HourTotal? =
        scrollDao.getPeakHourForDate(date)

    // ── Unlock reads ──────────────────────────────────────────────────────────
    fun unlockHistorySince(startDate: String): Flow<List<UnlockDay>> =
        unlockDao.getHistorySince(startDate)
    suspend fun unlockDay(date: String): UnlockDay? = unlockDao.getDay(date)
    suspend fun unlockHoursForDate(date: String): List<UnlockHour> =
        unlockDao.getHoursForDate(date)
    fun unlockPeakHourSince(startDate: String): Flow<HourTotal?> =
        unlockDao.getPeakHourSince(startDate)
    suspend fun unlockPeakHourForDate(date: String): HourTotal? =
        unlockDao.getPeakHourForDate(date)

    // ── App-scroll reads ──────────────────────────────────────────────────────
    fun appTotalsBetween(startDate: String, endDate: String): Flow<List<AppScrollTotal>> =
        appScrollDao.getTotalsBetween(startDate, endDate)

    // ── Wellness reads ────────────────────────────────────────────────────────
    fun wellnessHistorySince(startDate: String): Flow<List<WellnessDay>> =
        wellnessDao.getHistorySince(startDate)
    suspend fun wellnessDay(date: String): WellnessDay? = wellnessDao.getDay(date)

    // ── Atomic writes ───────────────────────────────────────────────────────────
    // Each wraps its multi-table write in a transaction so the day is written
    // all-or-nothing. This is the fix for the previously-uncoordinated writers:
    // a crash mid-write can no longer leave a half-saved day.

    /** All-or-nothing write of a day's scroll total + hourly buckets + per-app counts. */
    suspend fun persistScrollDay(
        date: String,
        count: Int,
        hours: Map<Int, Int>,
        apps: Map<String, Int>
    ) = db.withTransaction {
        scrollDao.insertOrUpdate(ScrollDay(date, count))
        hours.forEach { (hour, c) -> scrollDao.insertOrUpdateHour(ScrollHour(date, hour, c)) }
        apps.forEach  { (pkg,  c) -> appScrollDao.insertOrUpdate(AppScrollDay(date, pkg, c)) }
    }

    /** All-or-nothing write of a day's unlock summary + hourly unlock buckets. */
    suspend fun persistUnlockDay(day: UnlockDay, hours: Map<Int, Int>) = db.withTransaction {
        unlockDao.insertOrUpdate(day)
        hours.forEach { (hour, c) -> unlockDao.insertOrUpdateHour(UnlockHour(day.date, hour, c)) }
    }

    // Single-row upserts (no multi-table coupling, so no transaction needed)
    suspend fun upsertScrollDay(day: ScrollDay)     = scrollDao.insertOrUpdate(day)
    suspend fun upsertUnlockDay(day: UnlockDay)     = unlockDao.insertOrUpdate(day)
    suspend fun upsertWellnessDay(day: WellnessDay) = wellnessDao.insertOrUpdate(day)

    /**
     * Ensures every date in `[startDate, endDateExclusive)` has a persisted
     * ScrollDay row, inserting a zero-count row for any that are missing.
     *
     * The only normal writer of a day's row is ResetWorker firing at midnight.
     * If it misses a night — Doze, an OEM battery manager, the phone being off
     * — that day is otherwise silently absent from scroll_history forever,
     * which throws off anything that requires a full N-day window (e.g. the
     * 7-day scroll baseline). We can't recover the true count for a missed
     * day, so we record it as 0, identical to a real zero-usage day.
     *
     * Idempotent and safe to call on every app start — the query and inserts
     * are cheap, and a day that already has a row is left untouched.
     */
    suspend fun backfillMissingScrollDays(startDate: String, endDateExclusive: String) {
        val existing = scrollDao.getHistorySince(startDate).first().map { it.date }.toSet()
        DateRange.missingDates(existing, startDate, endDateExclusive)
            .forEach { date -> scrollDao.insertOrUpdate(ScrollDay(date, 0)) }
    }

    suspend fun deleteScrollHoursForDate(date: String) = scrollDao.deleteHoursForDate(date)

    suspend fun deleteAppScrollsForDate(date: String) = appScrollDao.deleteForDate(date)

    companion object {
        @Volatile private var INSTANCE: NudgeRepository? = null

        /** Process-wide singleton; safe to call from the service, worker, or VM. */
        fun get(context: Context): NudgeRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NudgeRepository(
                    ScrollDatabase.getDatabase(context.applicationContext)
                ).also { INSTANCE = it }
            }
    }
}
