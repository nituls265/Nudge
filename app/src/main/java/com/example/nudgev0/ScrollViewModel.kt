package com.example.nudgev0

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nudgev0.data.NudgeRepository
import com.example.nudgev0.data.ScrollDay
import com.example.nudgev0.data.UnlockDay
import com.example.nudgev0.data.WellnessDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ScrollViewModel(
    application: Application,
    private val repo: NudgeRepository
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    init {
        // Backfill wellness scores for any past day that has scroll data but no wellness entry.
        // Handles the case where ResetWorker was delayed by Doze / battery optimisation.
        viewModelScope.launch(Dispatchers.IO) { backfillMissingWellnessScores() }
    }

    // ── Live state from the service ───────────────────────────────────────────

    val scrollCount:       StateFlow<Int>   = MyAccessibilityService.scrollCount
    val scrollTimestamps:  StateFlow<List<Long>> = MyAccessibilityService.scrollTimestamps
    val isBubbleVisible:   StateFlow<Boolean> = MyAccessibilityService.isBubbleVisible
    val isPaused:          StateFlow<Boolean> = MyAccessibilityService.isPaused
    val interventionState: StateFlow<InterventionState> = MyAccessibilityService.interventionState

    // ── Laptop sync (Firebase Realtime Database) ──────────────────────────────

    val syncCode: StateFlow<String> = FirebaseSyncManager.syncCodeFlow

    private val today get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    val laptopCount: StateFlow<Int> = FirebaseSyncManager
        .laptopCountFlow(appContext, today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalScrollCount: StateFlow<Int> = combine(scrollCount, laptopCount) { phone, laptop ->
        phone + laptop
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // All laptop counts keyed by date — used for historical chart and breakdown
    val laptopHistory: StateFlow<Map<String, Int>> = FirebaseSyncManager
        .laptopHistoryFlow(appContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val unlockCount:       StateFlow<Int>   = MyAccessibilityService.unlockCount
    val firstUnlockMs:     StateFlow<Long>  = MyAccessibilityService.firstUnlockMs
    val lastUnlockMs:      StateFlow<Long>  = MyAccessibilityService.lastUnlockMs
    val avgSessionMin:     StateFlow<Float> = MyAccessibilityService.avgSessionMin
    val longestSessionMin: StateFlow<Int>   = MyAccessibilityService.longestSessionMin

    // ── Time range ────────────────────────────────────────────────────────────

    private val _timeRange = MutableStateFlow(7)
    val timeRange = _timeRange.asStateFlow()

    fun setTimeRange(days: Int) { _timeRange.value = days }

    // ── Scroll chart data ─────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val scrollChartData: StateFlow<List<ScrollDay>> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }
        val startDate = sdf.format(cal.time)

        repo.scrollHistorySince(startDate)
            .combine(MyAccessibilityService.scrollCount) { raw, liveCount -> Pair(raw, liveCount) }
            .combine(laptopHistory) { (raw, liveCount), laptopMap ->
                val today = sdf.format(Date())
                val map   = raw.associateBy { it.date }.toMutableMap()
                map[today] = ScrollDay(today, liveCount + (laptopMap[today] ?: 0))
                filledDays(days, sdf, map) { ScrollDay(it, 0) }
                    .map { day ->
                        if (day.date == today) day
                        else ScrollDay(day.date, day.count + (laptopMap[day.date] ?: 0))
                    }
                    .let { if (days == 90) weeklyAverage(it) else it }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Unlock chart data ─────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val unlockChartData: StateFlow<List<ScrollDay>> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }
        val startDate = sdf.format(cal.time)

        repo.unlockHistorySince(startDate).combine(MyAccessibilityService.unlockCount) { raw, liveCount ->
            val map = raw.associate { it.date to ScrollDay(it.date, it.count) }.toMutableMap()
            val today = sdf.format(Date())
            map[today] = ScrollDay(today, liveCount)
            filledDays(days, sdf, map) { ScrollDay(it, 0) }
                .let { if (days == 90) weeklyAverage(it) else it }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Screen time chart data ────────────────────────────────────────────────
    // Each day's value = total screen time in minutes (avgSessionMin × unlockCount)

    /** Live total screen time today in minutes */
    val todayScreenTimeMin: StateFlow<Int> = combine(avgSessionMin, unlockCount) { avg, count ->
        (avg * count).toInt()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val screenTimeChartData: StateFlow<List<ScrollDay>> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }
        val startDate = sdf.format(cal.time)

        val liveScreenTime = combine(
            MyAccessibilityService.avgSessionMin,
            MyAccessibilityService.unlockCount
        ) { avg, count -> (avg * count).toInt() }

        repo.unlockHistorySince(startDate).combine(liveScreenTime) { raw, liveMin ->
            val today = sdf.format(Date())
            val map = raw.associate { day ->
                day.date to ScrollDay(day.date, (day.avgSessionMin * day.count).toInt())
            }.toMutableMap()
            map[today] = ScrollDay(today, liveMin)
            filledDays(days, sdf, map) { ScrollDay(it, 0) }
                .let { if (days == 90) weeklyAverage(it) else it }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Peak hours ────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val peakScrollHour: StateFlow<String> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val start = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }.time)
        repo.scrollPeakHourSince(start).map { it?.let { h -> formatHourRange(h.hour) } ?: "No data" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "No data")

    @OptIn(ExperimentalCoroutinesApi::class)
    val peakUnlockHour: StateFlow<String> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val start = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }.time)
        repo.unlockPeakHourSince(start).map { it?.let { h -> formatHourRange(h.hour) } ?: "No data" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "No data")

    // ── App breakdown ─────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val appBreakdown: StateFlow<List<Pair<String, Int>>> = _timeRange.flatMapLatest { days ->
        val sdf   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val start = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }.time)

        repo.appTotalsBetween(start, today)
            .combine(MyAccessibilityService.appScrollCounts) { dbTotals, liveCounts ->
                Pair(dbTotals, liveCounts)
            }
            .combine(laptopHistory) { (dbTotals, liveCounts), laptopMap ->
                val combined = dbTotals.associate { it.packageName to it.total }.toMutableMap()
                liveCounts.forEach { (pkg, count) ->
                    combined[pkg] = (combined[pkg] ?: 0) + count
                }
                val laptopTotal = laptopMap.entries
                    .filter { it.key >= start && it.key <= today }
                    .sumOf { it.value }
                if (laptopTotal > 0) combined["laptop"] = laptopTotal

                combined.entries
                    .filter { !isSystemPackage(it.key) }
                    .sortedByDescending { it.value }
                    .map { it.toPair() }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 7-day scroll average for wellness baseline ────────────────────────────

    private val sevenDayScrollAvg: StateFlow<Float> = run {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val startDate = sdf.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.time
        )
        repo.scrollHistorySince(startDate)
            .map { dbDays ->
                val today = sdf.format(Date())
                // Phone-only baseline — do NOT add laptop history here.
                // Laptop was set up recently so past days have no laptop data;
                // mixing it with today's phone+laptop total makes the comparison unfair.
                val days = dbDays
                    .filter { it.date != today }
                    .map { it.count }
                    .filter { it >= 5 }   // ignore days with no meaningful usage
                if (days.size < 3) 0f     // need at least 3 real days for a baseline
                else days.average().toFloat()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)
    }

    // ── Live wellness score ───────────────────────────────────────────────────

    val wellnessScore: StateFlow<WellnessScore> = combine(
        scrollCount,        // phone-only — matches phone-only baseline in sevenDayScrollAvg
        sevenDayScrollAvg,
        unlockCount,
        avgSessionMin,
        longestSessionMin
    ) { a, b, c, d, e -> WnTuple5(a, b, c, d, e) }
        .combine(firstUnlockMs) { t, first -> WnTuple6(t.a, t.b, t.c, t.d, t.e, first) }
        .combine(lastUnlockMs)  { t, last  ->
            val liveTopApps = MyAccessibilityService.appScrollCounts.value
                .entries
                .filter { !isSystemPackage(it.key) }
                .sortedByDescending { it.value }
                .map { it.key to it.value }

            WellnessCalculator.calculate(
                todayScrolls      = t.a,
                sevenDayAvg       = t.b,
                unlockCount       = t.c,
                avgSessionMin     = t.d,
                longestSessionMin = t.e,
                firstUnlockMs     = t.f,
                lastUnlockMs      = last,
                topApps           = liveTopApps
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            WellnessCalculator.calculate(0, 0f, 0, 0f, 0, 0L, 0L, emptyList())
        )

    // ── Wellness history (trend chart) ────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val wellnessHistory: StateFlow<List<WellnessHistoryPoint>> =
        combine(_timeRange, wellnessScore) { days, liveScore -> Pair(days, liveScore) }
            .flatMapLatest { (days, liveScore) ->
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val startDate = sdf.format(
                    Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }.time
                )
                repo.wellnessHistorySince(startDate).map { dbDays ->
                    val today = sdf.format(Date())
                    val pointMap = dbDays.associate { d ->
                        d.date to WellnessHistoryPoint(
                            date             = d.date,
                            score            = d.score,
                            scrollVolume     = d.scrollVolume,
                            sessionBehaviour = d.sessionBehaviour,
                            unlockFrequency  = d.unlockFrequency,
                            timeHygiene      = d.timeHygiene,
                            appQuality       = d.appQuality
                        )
                    }.toMutableMap()
                    // Always inject the live score for today so it stays up-to-date
                    pointMap[today] = WellnessHistoryPoint(
                        date             = today,
                        score            = liveScore.total,
                        scrollVolume     = liveScore.scrollVolume,
                        sessionBehaviour = liveScore.sessionBehaviour,
                        unlockFrequency  = liveScore.unlockFrequency,
                        timeHygiene      = liveScore.timeHygiene,
                        appQuality       = liveScore.appQuality
                    )

                    filledDays(days, sdf, pointMap) { WellnessHistoryPoint(it, -1) }
                        .let { if (days == 90) weeklyWellnessAverage(it) else it }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 7-day fixed wellness window (HomeTab delta/trend — independent of UI range) ──
    // Always covers the last 7 calendar days so delta and trend labels are stable
    // regardless of which time-range the user has selected on the History tab.
    // Uses WhileSubscribed so it stops collecting when HomeTab is off-screen.
    private val recentWellnessHistory: StateFlow<List<WellnessHistoryPoint>> = run {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val startDate = sdf.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }.time
        )
        repo.wellnessHistorySince(startDate)
            .combine(wellnessScore) { dbDays, liveScore ->
                // Mirror the logic in wellnessHistory but for a fixed 7-day window.
                val today = sdf.format(Date())
                val map = dbDays.associate { d ->
                    d.date to WellnessHistoryPoint(
                        date             = d.date,
                        score            = d.score,
                        scrollVolume     = d.scrollVolume,
                        sessionBehaviour = d.sessionBehaviour,
                        unlockFrequency  = d.unlockFrequency,
                        timeHygiene      = d.timeHygiene,
                        appQuality       = d.appQuality
                    )
                }.toMutableMap()
                // Always inject live today score so the delta reflects the current moment
                map[today] = WellnessHistoryPoint(
                    date             = today,
                    score            = liveScore.total,
                    scrollVolume     = liveScore.scrollVolume,
                    sessionBehaviour = liveScore.sessionBehaviour,
                    unlockFrequency  = liveScore.unlockFrequency,
                    timeHygiene      = liveScore.timeHygiene,
                    appQuality       = liveScore.appQuality
                )
                filledDays(7, sdf, map) { WellnessHistoryPoint(it, -1) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    /**
     * How many points today's score is up or down vs yesterday.
     * Null until at least one full previous day exists in the DB.
     */
    val scoreDelta: StateFlow<Int?> = recentWellnessHistory.map { history ->
        val sdf      = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = sdf.format(Date())
        val yestStr  = sdf.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )
        val todayPts = history.find { it.date == todayStr }?.score?.takeIf { it >= 0 }
        val yestPts  = history.find { it.date == yestStr  }?.score?.takeIf { it >= 0 }
        if (todayPts != null && yestPts != null) todayPts - yestPts else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * "Best in X days" or "2nd best this week" — empty string when not noteworthy
     * or when there are fewer than 2 past days of data to compare against.
     */
    val scoreTrendLabel: StateFlow<String> = recentWellnessHistory.map { history ->
        val sdf      = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = sdf.format(Date())
        val todayPts = history.find { it.date == todayStr }?.score?.takeIf { it >= 0 }
            ?: return@map ""
        val pastScores = history
            .filter { it.date != todayStr && it.score >= 0 }
            .map { it.score }
        if (pastScores.size < 2) return@map ""   // need ≥ 2 past days to be meaningful
        val beaten = pastScores.count { todayPts > it }
        when {
            beaten == pastScores.size     -> "📈 Best in ${pastScores.size + 1} days"
            beaten >= pastScores.size - 1 -> "📊 2nd best this week"
            else                          -> ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == "unknown") return true
        val systemPrefixes = listOf(
            "android",
            "com.android.",
            "com.google.android.",
            "com.samsung.android.",
        )
        return systemPrefixes.any { pkg == it || pkg.startsWith(it) }
    }

    private fun <V> filledDays(
        days: Int,
        sdf: SimpleDateFormat,
        map: Map<String, V>,
        default: (String) -> V
    ): List<V> = (0 until days).map { i ->
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1) + i) }
        val dateStr = sdf.format(c.time)
        map[dateStr] ?: default(dateStr)
    }

    private fun weeklyAverage(list: List<ScrollDay>): List<ScrollDay> =
        list.chunked(7).map { week ->
            ScrollDay(week.first().date, week.map { it.count }.average().toInt())
        }

    private fun weeklyWellnessAverage(list: List<WellnessHistoryPoint>): List<WellnessHistoryPoint> =
        list.chunked(7).map { week ->
            val scored = week.filter { it.score >= 0 }
            if (scored.isEmpty()) {
                WellnessHistoryPoint(week.first().date, -1)
            } else {
                fun avg(sel: (WellnessHistoryPoint) -> Int) =
                    scored.map { sel(it) }.filter { it >= 0 }.let {
                        if (it.isEmpty()) -1 else it.average().toInt()
                    }
                WellnessHistoryPoint(
                    date             = week.first().date,
                    score            = scored.map { it.score }.average().toInt(),
                    scrollVolume     = avg { it.scrollVolume },
                    sessionBehaviour = avg { it.sessionBehaviour },
                    unlockFrequency  = avg { it.unlockFrequency },
                    timeHygiene      = avg { it.timeHygiene },
                    appQuality       = avg { it.appQuality }
                )
            }
        }

    private fun formatHourRange(hour: Int): String {
        val fmt   = SimpleDateFormat("h a", Locale.getDefault())
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0) }
        val end   = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, (hour + 1) % 24); set(Calendar.MINUTE, 0) }
        return "${fmt.format(start.time)} – ${fmt.format(end.time)}"
    }

    // ── Wellness backfill ─────────────────────────────────────────────────────
    // Runs once on startup. If ResetWorker was delayed (Doze, battery optimisation,
    // app not open at midnight), any past day with scroll data but no wellness row
    // gets its score computed retroactively from the DB.

    private suspend fun backfillMissingWellnessScores() {
        val sdf   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())

        val thirtyDaysBack = sdf.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }.time
        )

        try {
            val scrollDays   = repo.scrollHistorySince(thirtyDaysBack).firstOrNull()
                               ?: return
            val wellnessDates = repo.wellnessHistorySince(thirtyDaysBack).firstOrNull()
                               ?.map { it.date }?.toSet() ?: emptySet()

            // Only backfill past days (not today — live score handles today)
            val missing = scrollDays.filter { it.date != today && it.date !in wellnessDates }
            if (missing.isEmpty()) return

            for (scrollDay in missing) {
                val date = scrollDay.date

                // Date after this day (needed for the exclusive-end app-scroll query)
                val nextDate = sdf.format(
                    Calendar.getInstance().apply {
                        time = sdf.parse(date)!!
                        add(Calendar.DAY_OF_YEAR, 1)
                    }.time
                )

                val unlockDay = repo.unlockDay(date)

                val appTotals = repo.appTotalsBetween(date, nextDate)
                    .firstOrNull() ?: emptyList()

                val topApps = appTotals
                    .filter { !isSystemPackage(it.packageName) }
                    .sortedByDescending { it.total }
                    .map { it.packageName to it.total }

                // 7-day average: scroll days strictly before this date
                val sevenDaysBack = sdf.format(
                    Calendar.getInstance().apply {
                        time = sdf.parse(date)!!
                        add(Calendar.DAY_OF_YEAR, -7)
                    }.time
                )
                val prior = repo.scrollHistorySince(sevenDaysBack).firstOrNull()
                    ?.filter { it.date < date }?.map { it.count }?.filter { it >= 5 }
                    ?: emptyList()
                val sevenDayAvg = if (prior.size < 3) 0f else prior.average().toFloat()

                val score = WellnessCalculator.calculate(
                    todayScrolls      = scrollDay.count,
                    sevenDayAvg       = sevenDayAvg,
                    unlockCount       = unlockDay?.count ?: 0,
                    avgSessionMin     = unlockDay?.avgSessionMin ?: 0f,
                    longestSessionMin = unlockDay?.longestSessionMin ?: 0,
                    firstUnlockMs     = unlockDay?.firstUnlockMs ?: 0L,
                    lastUnlockMs      = unlockDay?.lastUnlockMs ?: 0L,
                    topApps           = topApps
                )

                repo.upsertWellnessDay(
                    WellnessDay(
                        date             = date,
                        score            = score.total,
                        tier             = score.tier.name,
                        scrollVolume     = score.scrollVolume,
                        sessionBehaviour = score.sessionBehaviour,
                        unlockFrequency  = score.unlockFrequency,
                        timeHygiene      = score.timeHygiene,
                        appQuality       = score.appQuality
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Historical bar-tap insights ───────────────────────────────────────────

    /** Fetched once per bar tap — all data for a specific past date. */
    data class HistoricalInsights(
        val date: String,
        val unlockDay: UnlockDay?,
        val scrollPeakHour: String,
        val unlockPeakHour: String,
        val appBreakdown: List<Pair<String, Int>>
    )

    suspend fun fetchHistoricalInsights(date: String): HistoricalInsights =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val unlockDay = repo.unlockDay(date)

            val scrollPeak = repo.scrollPeakHourForDate(date)
                ?.let { formatHourRange(it.hour) } ?: "No data"

            val unlockPeak = repo.unlockPeakHourForDate(date)
                ?.let { formatHourRange(it.hour) } ?: "No data"

            // App breakdown: date only (exclusive end = next day)
            val nextDate = sdf.format(
                Calendar.getInstance().apply {
                    time = sdf.parse(date)!!
                    add(Calendar.DAY_OF_YEAR, 1)
                }.time
            )
            val appTotals = repo.appTotalsBetween(date, nextDate).firstOrNull()
                ?: emptyList()
            val appBreakdown = appTotals
                .filter { !isSystemPackage(it.packageName) }
                .sortedByDescending { it.total }
                .map { it.packageName to it.total }

            HistoricalInsights(date, unlockDay, scrollPeak, unlockPeak, appBreakdown)
        }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun togglePause() {
        MyAccessibilityService.togglePause()
        AnalyticsHelper.logPauseToggled(isPaused.value)
    }

    fun toggleBubble() {
        MyAccessibilityService.toggleBubbleVisibility()
        AnalyticsHelper.logBubbleToggled(isBubbleVisible.value)
    }

    fun resetScrollCount() {
        MyAccessibilityService.resetScrollCount()
        AnalyticsHelper.logManualReset()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteScrollHoursForDate(today)
        }
    }
}

// ── Tiny tuple helpers (avoid boxing arrays) ──────────────────────────────────

private data class WnTuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
private data class WnTuple6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)

// ── Wellness history point ────────────────────────────────────────────────────

data class WellnessHistoryPoint(
    val date: String,
    val score: Int,              // -1 = no data
    val scrollVolume: Int     = -1,
    val sessionBehaviour: Int = -1,
    val unlockFrequency: Int  = -1,
    val timeHygiene: Int      = -1,
    val appQuality: Int       = -1
)
