package com.nitulshah.nudge.data

/**
 * Pure date-key arithmetic shared by every "which days are missing from the
 * DB" computation in the app (scroll history, wellness backfill, etc.). Kept
 * dependency-free so it's trivially unit-testable on the JVM.
 */
object DateRange {

    /**
     * All yyyy-MM-dd keys in `[startDate, endDateExclusive)` that are not in
     * [existing]. Returns an empty list if `startDate >= endDateExclusive`.
     */
    fun missingDates(
        existing: Set<String>,
        startDate: String,
        endDateExclusive: String
    ): List<String> =
        generateSequence(startDate) { DayBoundary.shift(it, 1) }
            .takeWhile { it < endDateExclusive }
            .filterNot { it in existing }
            .toList()
}
