package com.nitulshah.nudge.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The single source of truth for "what day is it" across the app.
 *
 * Before this existed, the yyyy-MM-dd day key was computed ad-hoc in ~25 places
 * (the accessibility service, ResetWorker, the ViewModel's chart queries,
 * MainActivity's midnight scheduler, Firebase paths). Every midnight-rollover
 * bug we have fixed traced back to one of those copies drifting from the others.
 * Centralising the logic here means there is exactly one definition of "today",
 * "yesterday", "N days ago", and "next midnight", and one place to test them.
 *
 * Two deliberate correctness choices:
 *
 *  1. Keys are formatted with [Locale.US], NOT Locale.getDefault(). The day key
 *     is a machine identifier (DB primary key, Firebase path, equality compare),
 *     so it must be stable Gregorian ASCII on every device. With the default
 *     locale, SimpleDateFormat("yyyy-MM-dd") emits Buddhist-era years in a Thai
 *     locale ("2569" instead of "2026") and non-ASCII digits in some Arabic
 *     locales — either silently corrupts every key. Display formatting (e.g.
 *     "MMM d") is a separate concern and intentionally still uses the default
 *     locale elsewhere.
 *
 *  2. All boundaries are computed in the device's LOCAL timezone (the default
 *     Calendar / Date behaviour), matching how the user experiences "a day".
 *     A traveller crossing timezones rolls over at their new local midnight.
 *
 * Thread-safety: SimpleDateFormat is not thread-safe and day keys are produced
 * from both the main thread (service/UI) and IO threads (workers/coroutines).
 * Each thread gets its own formatter via ThreadLocal, so there is no shared
 * mutable state and no per-call allocation in steady state.
 */
object DayBoundary {

    const val KEY_PATTERN = "yyyy-MM-dd"

    private val formatter = ThreadLocal.withInitial {
        SimpleDateFormat(KEY_PATTERN, Locale.US)
    }

    private fun fmt(): SimpleDateFormat = formatter.get()!!

    /** Today's key in the device's local timezone, e.g. "2026-06-05". */
    fun today(): String = fmt().format(Date())

    /** The day key containing the given epoch-millis instant (local timezone). */
    fun keyOf(millis: Long): String = fmt().format(Date(millis))

    /**
     * Key for the day `days` before today (local timezone).
     * `daysAgo(1)` = yesterday, `daysAgo(0)` = today.
     */
    fun daysAgo(days: Int): String = fmt().format(
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.time
    )

    /**
     * Shift a yyyy-MM-dd key by `delta` days (e.g. `shift(date, 1)` = the next
     * day, used for exclusive-end range queries). Returns the input unchanged if
     * it cannot be parsed, so callers never crash on bad data.
     */
    fun shift(dateKey: String, delta: Int): String {
        val parsed = parseOrNull(dateKey) ?: return dateKey
        return fmt().format(
            Calendar.getInstance().apply {
                time = parsed
                add(Calendar.DAY_OF_YEAR, delta)
            }.time
        )
    }

    /** Parse a yyyy-MM-dd key to a Date at local midnight, or null if malformed. */
    fun parseOrNull(dateKey: String): Date? =
        runCatching { fmt().parse(dateKey) }.getOrNull()

    /** Epoch millis of local midnight that STARTS the day containing `now`. */
    fun startOfDayMillis(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Epoch millis of the next local midnight strictly after `now`. Used both to
     * schedule the daily ResetWorker and to bound the service's per-day cache.
     */
    fun nextMidnightMillis(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
