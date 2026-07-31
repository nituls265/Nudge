package com.nitulshah.nudge

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nitulshah.nudge.data.DayBoundary
import com.nitulshah.nudge.data.NudgeRepository
import com.nitulshah.nudge.data.ScrollDay
import com.nitulshah.nudge.data.UnlockDay
import com.nitulshah.nudge.data.WellnessDay
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

    private val _previousDayLastUnlockMs = MutableStateFlow(0L)
    private val _personalAvgFirstUnlockMinute = MutableStateFlow<Int?>(null)

    init {
        // Backfill wellness scores for any past day that has scroll data but no wellness entry.
        // Handles the case where ResetWorker was delayed by Doze / battery optimisation.
        viewModelScope.launch(Dispatchers.IO) { backfillMissingWellnessScores() }
        // Yesterday's last-unlock time and the personal first-unlock baseline are both
        // fixed for the whole day (nothing before today changes intraday), so one-shot
        // fetches are enough — no need for reactive flows.
        viewModelScope.launch(Dispatchers.IO) {
            _previousDayLastUnlockMs.value = repo.unlockDay(DayBoundary.daysAgo(1))?.lastUnlockMs ?: 0L
        }
        viewModelScope.launch(Dispatchers.IO) {
            val today = DayBoundary.today()
            val pastFirstUnlocks = repo.unlockHistorySince(DayBoundary.daysAgo(14)).firstOrNull()
                ?.filter { it.date != today && it.firstUnlockMs > 0L }
                ?.map { it.firstUnlockMs }
                ?: emptyList()
            _personalAvgFirstUnlockMinute.value = WellnessCalculator.averageFirstUnlockMinute(pastFirstUnlocks)
        }
    }

    // ── Live state from the service ───────────────────────────────────────────

    val scrollCount:       StateFlow<Int>   = MyAccessibilityService.scrollCount
    val scrollTimestamps:  StateFlow<List<Long>> = MyAccessibilityService.scrollTimestamps
    val isBubbleVisible:   StateFlow<Boolean> = MyAccessibilityService.isBubbleVisible
    val isPaused:          StateFlow<Boolean> = MyAccessibilityService.isPaused
    val interventionState: StateFlow<InterventionState> = MyAccessibilityService.interventionState

    // ── Laptop sync (Supabase) ────────────────────────────────────────────────

    val syncCode: StateFlow<String> = SyncManager.syncCodeFlow

    private val today get() = DayBoundary.today()

    val laptopCount: StateFlow<Int> = SyncManager
        .laptopCountFlow(appContext, today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalScrollCount: StateFlow<Int> = combine(scrollCount, laptopCount) { phone, laptop ->
        phone + laptop
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // All laptop counts keyed by date — used for historical chart and breakdown
    val laptopHistory: StateFlow<Map<String, Int>> = SyncManager
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
        val startDate = DayBoundary.daysAgo(days - 1)

        repo.scrollHistorySince(startDate)
            .combine(MyAccessibilityService.scrollCount) { raw, liveCount -> Pair(raw, liveCount) }
            .combine(laptopHistory) { (raw, liveCount), laptopMap ->
                val today = DayBoundary.today()
                val map   = raw.associateBy { it.date }.toMutableMap()
                map[today] = ScrollDay(today, liveCount + (laptopMap[today] ?: 0))
                filledDays(days, map) { ScrollDay(it, 0) }
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
        val startDate = DayBoundary.daysAgo(days - 1)

        repo.unlockHistorySince(startDate).combine(MyAccessibilityService.unlockCount) { raw, liveCount ->
            val map = raw.associate { it.date to ScrollDay(it.date, it.count) }.toMutableMap()
            val today = DayBoundary.today()
            map[today] = ScrollDay(today, liveCount)
            filledDays(days, map) { ScrollDay(it, 0) }
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
        val startDate = DayBoundary.daysAgo(days - 1)

        val liveScreenTime = combine(
            MyAccessibilityService.avgSessionMin,
            MyAccessibilityService.unlockCount
        ) { avg, count -> (avg * count).toInt() }

        repo.unlockHistorySince(startDate).combine(liveScreenTime) { raw, liveMin ->
            val today = DayBoundary.today()
            val map = raw.associate { day ->
                day.date to ScrollDay(day.date, (day.avgSessionMin * day.count).toInt())
            }.toMutableMap()
            map[today] = ScrollDay(today, liveMin)
            filledDays(days, map) { ScrollDay(it, 0) }
                .let { if (days == 90) weeklyAverage(it) else it }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Peak hours ────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val peakScrollHour: StateFlow<String> = _timeRange.flatMapLatest { days ->
        val start = DayBoundary.daysAgo(days - 1)
        repo.scrollPeakHourSince(start).map { it?.let { h -> formatHourRange(h.hour) } ?: "No data" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "No data")

    @OptIn(ExperimentalCoroutinesApi::class)
    val peakUnlockHour: StateFlow<String> = _timeRange.flatMapLatest { days ->
        val start = DayBoundary.daysAgo(days - 1)
        repo.unlockPeakHourSince(start).map { it?.let { h -> formatHourRange(h.hour) } ?: "No data" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "No data")

    // ── App breakdown ─────────────────────────────────────────────────────────

    // Scroll sources aggregated over the selected time range (7 / 30 / 90 days).
    // The DB query already SUMs per-app counts across the range; laptop counts
    // are summed from laptopHistory over the same window.
    // Tapping a past bar in the chart swaps in that day's breakdown via
    // fetchHistoricalInsights() — this flow covers the "no bar selected" state.
    @OptIn(ExperimentalCoroutinesApi::class)
    val appBreakdown: StateFlow<List<Pair<String, Int>>> =
        _timeRange.flatMapLatest { days ->
            val startDate = DayBoundary.daysAgo(days - 1)
            val endDate   = DayBoundary.shift(today, 1)
            combine(
                repo.appTotalsBetween(startDate, endDate),
                laptopHistory
            ) { dbRows, laptopMap ->
                val apps = dbRows
                    .filter { !isSystemPackage(it.packageName) }
                    .associate { it.packageName to it.total }
                    .toMutableMap()
                val laptopTotal = laptopMap.entries
                    .filter { it.key >= startDate }
                    .sumOf { it.value }
                if (laptopTotal > 0) apps["laptop"] = laptopTotal
                apps.entries.sortedByDescending { it.value }.map { it.toPair() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 7-day scroll average for wellness baseline ────────────────────────────

    private val sevenDayScrollAvg: StateFlow<Float> = run {
        val startDate = DayBoundary.daysAgo(7)
        repo.scrollHistorySince(startDate)
            .map { dbDays ->
                val today = DayBoundary.today()
                // Phone-only baseline — do NOT add laptop history here.
                // Laptop was set up recently so past days have no laptop data;
                // mixing it with today's phone+laptop total makes the comparison unfair.
                val days = dbDays
                    .filter { it.date != today }
                    .map { it.count }
                    .filter { it >= 5 }   // ignore days with no meaningful usage
                // Require the full 7-day window, not just a handful of days:
                // weekday and weekend scroll behavior plausibly come from
                // different distributions, so a partial sample (e.g. 3 days
                // that happen to land on a weekend) can skew the "normal"
                // baseline it's meant to represent. Only a full week
                // guarantees both regimes are captured.
                if (days.size < 7) 0f
                else days.average().toFloat()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)
    }

    // ── Data-driven calibration re-trigger ────────────────────────────────────
    // The 7-day calibration window in HomeTab/SettingsSheet is a one-time
    // check against install date, so it can't tell "brand new" apart from
    // "the underlying history got wiped" (e.g. a scroll-count reset during
    // testing). This mirrors sevenDayScrollAvg's full-7-day requirement but
    // without the >=5-scroll filter, so a genuinely light usage day still
    // counts as "we have data" — it only flips back on when history rows are
    // actually missing, not just when usage is light.
    val scrollBaselineDaysRemaining: StateFlow<Int> = run {
        val startDate = DayBoundary.daysAgo(7)
        repo.scrollHistorySince(startDate)
            .map { dbDays ->
                val today = DayBoundary.today()
                val validDays = dbDays.count { it.date != today }
                maxOf(0, 7 - validDays)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)
    }

    // ── Live wellness score ───────────────────────────────────────────────────

    val wellnessScore: StateFlow<WellnessScore> = combine(
        totalScrollCount,   // phone + laptop — matches the "total scroll count" shown in the UI
        sevenDayScrollAvg,
        unlockCount,
        avgSessionMin,
        longestSessionMin
    ) { a, b, c, d, e -> WnTuple5(a, b, c, d, e) }
        .combine(firstUnlockMs) { t, first -> WnTuple6(t.a, t.b, t.c, t.d, t.e, first) }
        .combine(lastUnlockMs)  { t, last  -> WnTuple7(t.a, t.b, t.c, t.d, t.e, t.f, last) }
        .combine(
            combine(_previousDayLastUnlockMs, _personalAvgFirstUnlockMinute) { prevLast, avgMinute ->
                prevLast to avgMinute
            }
        ) { t, (prevLast, avgMinute) ->
            val liveTopApps = MyAccessibilityService.appScrollCounts.value
                .entries
                .filter { !isSystemPackage(it.key) }
                .sortedByDescending { it.value }
                .map { it.key to it.value }

            WellnessCalculator.calculate(
                todayScrolls                  = t.a,
                sevenDayAvg                   = t.b,
                unlockCount                   = t.c,
                avgSessionMin                 = t.d,
                longestSessionMin             = t.e,
                firstUnlockMs                 = t.f,
                lastUnlockMs                  = t.g,
                topApps                       = liveTopApps,
                previousDayLastUnlockMs       = prevLast,
                personalAvgFirstUnlockMinute  = avgMinute
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
                val startDate = DayBoundary.daysAgo(days - 1)
                repo.wellnessHistorySince(startDate).map { dbDays ->
                    val today = DayBoundary.today()
                    val pointMap = dbDays.associate { d ->
                        d.date to WellnessHistoryPoint(
                            date             = d.date,
                            score            = d.score,
                            scrollVolume     = d.scrollVolume,
                            sessionBehaviour = d.sessionBehaviour,
                            unlockFrequency  = d.unlockFrequency,
                            timeHygiene      = d.timeHygiene,
                            appQuality       = d.appQuality,
                            bedtimeScore     = d.bedtimeScore,
                            gapScore         = d.gapScore,
                            consistencyScore = d.consistencyScore
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
                        appQuality       = liveScore.appQuality,
                        bedtimeScore     = liveScore.bedtimeScore,
                        gapScore         = liveScore.gapScore,
                        consistencyScore = liveScore.consistencyScore
                    )

                    filledDays(days, pointMap) { WellnessHistoryPoint(it, -1) }
                        .let { if (days == 90) weeklyWellnessAverage(it) else it }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 7-day fixed wellness window (HomeTab delta/trend — independent of UI range) ──
    // Always covers the last 7 calendar days so delta and trend labels are stable
    // regardless of which time-range the user has selected on the History tab.
    // Uses WhileSubscribed so it stops collecting when HomeTab is off-screen.
    private val recentWellnessHistory: StateFlow<List<WellnessHistoryPoint>> = run {
        val startDate = DayBoundary.daysAgo(6)
        repo.wellnessHistorySince(startDate)
            .combine(wellnessScore) { dbDays, liveScore ->
                // Mirror the logic in wellnessHistory but for a fixed 7-day window.
                val today = DayBoundary.today()
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
                filledDays(7, map) { WellnessHistoryPoint(it, -1) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    /**
     * "Best in X days" or "2nd best this week" — empty string when not noteworthy
     * or when there are fewer than 2 past days of data to compare against.
     */
    val scoreTrendLabel: StateFlow<String> = recentWellnessHistory.map { history ->
        val todayStr = DayBoundary.today()
        val todayPts = history.find { it.date == todayStr }?.score?.takeIf { it >= 0 }
            ?: return@map ""
        val pastScores = history
            .filter { it.date != todayStr && it.score >= 0 }
            .map { it.score }
        if (pastScores.size < 2) return@map ""   // need ≥ 2 past days to be meaningful
        val beaten = pastScores.count { todayPts > it }
        when {
            beaten == pastScores.size     -> "Best in ${pastScores.size + 1} days"
            beaten >= pastScores.size - 1 -> "2nd best this week"
            else                          -> ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * 30-day rolling average of past daily wellness scores (today excluded) — the
     * slow, steady baseline today's live score is nudging up or down. Null until
     * at least 3 real past days exist in the DB, mirroring the baseline guard used
     * by sevenDayScrollAvg / scoreTrendLabel.
     */
    val overallAverage: StateFlow<Int?> = repo.wellnessHistorySince(DayBoundary.daysAgo(30))
        .map { dbDays ->
            val today = DayBoundary.today()
            val pastScores = dbDays.filter { it.date != today && it.score >= 0 }.map { it.score }
            if (pastScores.size < 3) null else pastScores.average().toInt()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * How many points today's live score currently sits above (or below) the
     * 30-day average — this, not the average itself, is the number meant to
     * motivate: today is what moves it.
     */
    val overallDelta: StateFlow<Int?> = combine(wellnessScore, overallAverage) { live, avg ->
        avg?.let { live.total - it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
        map: Map<String, V>,
        default: (String) -> V
    ): List<V> = (0 until days).map { i ->
        // i = 0 is the oldest day in the window, i = days-1 is today.
        val dateStr = DayBoundary.daysAgo(days - 1 - i)
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
    // Runs once on startup. If ResetWorker was delayed or skipped entirely (Doze,
    // battery optimisation, device powered off, app not open at midnight), any past
    // day with no wellness row — including zero-usage days with no ScrollDay row —
    // gets its score computed retroactively from the DB.

    private suspend fun backfillMissingWellnessScores() {
        val today = DayBoundary.today()
        val thirtyDaysBack = DayBoundary.daysAgo(30)

        try {
            val scrollDays   = repo.scrollHistorySince(thirtyDaysBack).firstOrNull()
                               ?: emptyList()
            val scrollByDate = scrollDays.associateBy { it.date }
            val wellnessDates = repo.wellnessHistorySince(thirtyDaysBack).firstOrNull()
                               ?.map { it.date }?.toSet() ?: emptySet()

            // Walk every date in the window, not just dates with an existing ScrollDay
            // row — a day with zero phone usage never gets one, but still needs a score.
            val allDates = generateSequence(thirtyDaysBack) { DayBoundary.shift(it, 1) }
                .takeWhile { it <= today }

            // Only backfill past days (not today — live score handles today)
            val missing = allDates.filter { it != today && it !in wellnessDates }.toList()
            if (missing.isEmpty()) return

            for (date in missing) {
                val scrollCount = scrollByDate[date]?.count ?: 0

                // Date after this day (needed for the exclusive-end app-scroll query)
                val nextDate = DayBoundary.shift(date, 1)

                val unlockDay = repo.unlockDay(date)
                val prevDayLastUnlockMs = repo.unlockDay(DayBoundary.shift(date, -1))?.lastUnlockMs ?: 0L

                // Personal first-unlock baseline: 14 days before this date, excluding
                // this date itself and any zero-unlock days.
                val baselineHistory = repo.unlockHistorySince(DayBoundary.shift(date, -15))
                    .firstOrNull() ?: emptyList()
                val pastFirstUnlocks = baselineHistory
                    .filter { it.date != date && it.firstUnlockMs > 0L }
                    .map { it.firstUnlockMs }
                val avgFirstUnlockMinute = WellnessCalculator.averageFirstUnlockMinute(pastFirstUnlocks)

                val appTotals = repo.appTotalsBetween(date, nextDate)
                    .firstOrNull() ?: emptyList()

                val topApps = appTotals
                    .filter { !isSystemPackage(it.packageName) }
                    .sortedByDescending { it.total }
                    .map { it.packageName to it.total }

                // 7-day average: scroll days strictly before this date
                val sevenDaysBack = DayBoundary.shift(date, -7)
                val prior = repo.scrollHistorySince(sevenDaysBack).firstOrNull()
                    ?.filter { it.date < date }?.map { it.count }?.filter { it >= 5 }
                    ?: emptyList()
                val sevenDayAvg = if (prior.size < 3) 0f else prior.average().toFloat()

                val score = WellnessCalculator.calculate(
                    todayScrolls                  = scrollCount,
                    sevenDayAvg                   = sevenDayAvg,
                    unlockCount                   = unlockDay?.count ?: 0,
                    avgSessionMin                 = unlockDay?.avgSessionMin ?: 0f,
                    longestSessionMin             = unlockDay?.longestSessionMin ?: 0,
                    firstUnlockMs                 = unlockDay?.firstUnlockMs ?: 0L,
                    lastUnlockMs                  = unlockDay?.lastUnlockMs ?: 0L,
                    topApps                       = topApps,
                    previousDayLastUnlockMs       = prevDayLastUnlockMs,
                    personalAvgFirstUnlockMinute  = avgFirstUnlockMinute
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
                        appQuality       = score.appQuality,
                        bedtimeScore     = score.bedtimeScore,
                        gapScore         = score.gapScore,
                        consistencyScore = score.consistencyScore
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
            val unlockDay = repo.unlockDay(date)

            val scrollPeak = repo.scrollPeakHourForDate(date)
                ?.let { formatHourRange(it.hour) } ?: "No data"

            val unlockPeak = repo.unlockPeakHourForDate(date)
                ?.let { formatHourRange(it.hour) } ?: "No data"

            // App breakdown: date only (exclusive end = next day)
            val nextDate = DayBoundary.shift(date, 1)
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
        MyAccessibilityService.resetScrollCount()   // clears live in-memory state
        AnalyticsHelper.logManualReset()
        val today = DayBoundary.today()

        // Fully wipe today's PERSISTED scroll data so it re-accumulates
        // consistently (total, per-app rows, and hourly buckets). Setting the day
        // total to 0 — rather than leaving the old row — also stops the
        // onServiceConnected DB-fallback from ratcheting the live count back up
        // on the next service restart.
        viewModelScope.launch(Dispatchers.IO) {
            repo.upsertScrollDay(com.nitulshah.nudge.data.ScrollDay(today, 0))
            repo.deleteAppScrollsForDate(today)
            repo.deleteScrollHoursForDate(today)
        }

        // Reset the SharedPreferences recovery snapshot too, so a service restart
        // can't restore the stale count / per-app map.
        appContext.getSharedPreferences("NudgePrefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putInt("CURRENT_SCROLL_COUNT", 0)
            .putString("APP_SCROLL_COUNTS", "{}")
            .putString("LAST_SCROLL_DATE", today)
            .apply()
    }
}

// ── Tiny tuple helpers (avoid boxing arrays) ──────────────────────────────────

private data class WnTuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
private data class WnTuple6<A, B, C, D, E, F>(val a: A, val b: B, val c: C, val d: D, val e: E, val f: F)
private data class WnTuple7<A, B, C, D, E, F, G>(
    val a: A, val b: B, val c: C, val d: D, val e: E, val f: F, val g: G
)

// ── Wellness history point ────────────────────────────────────────────────────

data class WellnessHistoryPoint(
    val date: String,
    val score: Int,              // -1 = no data
    val scrollVolume: Int     = -1,
    val sessionBehaviour: Int = -1,
    val unlockFrequency: Int  = -1,
    val timeHygiene: Int      = -1,
    val appQuality: Int       = -1,
    val bedtimeScore: Int     = -1,
    val gapScore: Int         = -1,
    val consistencyScore: Int = -1
)
