package com.example.nudgev0

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nudgev0.data.AppScrollDao
import com.example.nudgev0.data.ScrollDao
import com.example.nudgev0.data.ScrollDay
import com.example.nudgev0.data.UnlockDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ScrollViewModel(
    application: Application,
    private val scrollDao: ScrollDao,
    private val unlockDao: UnlockDao,
    private val appScrollDao: AppScrollDao
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    // ── Live state from the service ───────────────────────────────────────────

    val scrollCount:       StateFlow<Int>   = MyAccessibilityService.scrollCount
    val scrollTimestamps:  StateFlow<List<Long>> = MyAccessibilityService.scrollTimestamps
    val isBubbleVisible:   StateFlow<Boolean> = MyAccessibilityService.isBubbleVisible
    val isPaused:          StateFlow<Boolean> = MyAccessibilityService.isPaused
    val interventionState: StateFlow<InterventionState> = MyAccessibilityService.interventionState

    // ── Laptop sync (Firebase Realtime Database) ──────────────────────────────

    val syncCode: String get() = FirebaseSyncManager.getSyncCode(appContext)

    private val today get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    val laptopCount: StateFlow<Int> = FirebaseSyncManager
        .laptopCountFlow(appContext, today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalScrollCount: StateFlow<Int> = combine(scrollCount, laptopCount) { phone, laptop ->
        phone + laptop
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

        scrollDao.getHistorySince(startDate).combine(MyAccessibilityService.scrollCount) { raw, liveCount ->
            val map = raw.associateBy { it.date }.toMutableMap()
            val today = sdf.format(Date())
            map[today] = ScrollDay(today, liveCount)
            filledDays(days, sdf, map) { ScrollDay(it, 0) }
                .let { if (days == 90) weeklyAverage(it) else it }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Unlock chart data ─────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val unlockChartData: StateFlow<List<ScrollDay>> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }
        val startDate = sdf.format(cal.time)

        unlockDao.getHistorySince(startDate).combine(MyAccessibilityService.unlockCount) { raw, liveCount ->
            val map = raw.associate { it.date to ScrollDay(it.date, it.count) }.toMutableMap()
            val today = sdf.format(Date())
            map[today] = ScrollDay(today, liveCount)
            filledDays(days, sdf, map) { ScrollDay(it, 0) }
                .let { if (days == 90) weeklyAverage(it) else it }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Peak hours ────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val peakScrollHour: StateFlow<String> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val start = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }.time)
        scrollDao.getPeakHourSince(start).map { it?.let { h -> formatHourRange(h.hour) } ?: "No data" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "No data")

    @OptIn(ExperimentalCoroutinesApi::class)
    val peakUnlockHour: StateFlow<String> = _timeRange.flatMapLatest { days ->
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val start = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }.time)
        unlockDao.getPeakHourSince(start).map { it?.let { h -> formatHourRange(h.hour) } ?: "No data" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "No data")

    // ── App breakdown ─────────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    val appBreakdown: StateFlow<List<Pair<String, Int>>> = _timeRange.flatMapLatest { days ->
        val sdf   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val start = sdf.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days + 1) }.time)

        appScrollDao.getTotalsBetween(start, today)
            .combine(MyAccessibilityService.appScrollCounts) { dbTotals, liveCounts ->
                val combined = dbTotals.associate { it.packageName to it.total }.toMutableMap()
                liveCounts.forEach { (pkg, count) ->
                    combined[pkg] = (combined[pkg] ?: 0) + count
                }
                combined.entries
                    .filter { !isSystemPackage(it.key) }
                    .sortedByDescending { it.value }
                    .map { it.toPair() }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == "unknown") return true
        val systemPrefixes = listOf(
            "android",               // base Android OS ("Android")
            "com.android.",          // System UI, launcher, settings, etc.
            "com.google.android.",   // GMS, Play Services, etc.
            "com.samsung.android.",  // Samsung system apps
        )
        return systemPrefixes.any { pkg == it || pkg.startsWith(it) }
    }

    private fun filledDays(
        days: Int,
        sdf: SimpleDateFormat,
        map: Map<String, ScrollDay>,
        default: (String) -> ScrollDay
    ): List<ScrollDay> = (0 until days).map { i ->
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -(days - 1) + i) }
        val dateStr = sdf.format(c.time)
        map[dateStr] ?: default(dateStr)
    }

    private fun weeklyAverage(list: List<ScrollDay>): List<ScrollDay> =
        list.chunked(7).map { week ->
            ScrollDay(week.first().date, week.map { it.count }.average().toInt())
        }

    private fun formatHourRange(hour: Int): String {
        val fmt = SimpleDateFormat("h a", Locale.getDefault())
        val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0) }
        val end   = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, (hour + 1) % 24); set(Calendar.MINUTE, 0) }
        return "${fmt.format(start.time)} – ${fmt.format(end.time)}"
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
            scrollDao.deleteHoursForDate(today)
        }
    }
}
