package com.nitulshah.nudge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

// ── HistoryTab ────────────────────────────────────────────────────────────────

@Composable
fun HistoryTab(vm: ScrollViewModel, onSettingsClick: () -> Unit) {

    // ── State — only subscribe to what this tab actually renders ──────────────
    val selectedRange    by vm.timeRange.collectAsState()
    val wellnessHistory  by vm.wellnessHistory.collectAsState()
    val scrollChart      by vm.scrollChartData.collectAsState()
    val unlockChart      by vm.unlockChartData.collectAsState()
    val screenTimeChart  by vm.screenTimeChartData.collectAsState()
    val peakScrollHour   by vm.peakScrollHour.collectAsState()
    val peakUnlockHour   by vm.peakUnlockHour.collectAsState()
    val firstUnlockMs    by vm.firstUnlockMs.collectAsState()
    val lastUnlockMs     by vm.lastUnlockMs.collectAsState()
    val avgSessionMin    by vm.avgSessionMin.collectAsState()
    val longestSessionMin by vm.longestSessionMin.collectAsState()
    val appBreakdown     by vm.appBreakdown.collectAsState()
    val unlockCount      by vm.unlockCount.collectAsState()
    val screenTimeMin    by vm.todayScreenTimeMin.collectAsState()

    // ── Local UI state ────────────────────────────────────────────────────────
    // metricTab drives the raw-metrics chart (secondary section)
    var metricTab by remember { mutableStateOf("scrolls") }

    // chartSelectedIndex resets whenever the active chart or time range changes
    // so stale selections never carry over to a different dataset
    var chartSelectedIndex   by remember(metricTab, selectedRange) { mutableStateOf<Int?>(null) }
    var wellnessSelectedIdx  by remember(selectedRange) { mutableStateOf<Int?>(null) }
    var hygieneSelectedIdx   by remember(selectedRange) { mutableStateOf<Int?>(null) }

    val accentColor = when (metricTab) {
        "scrolls" -> MetricScrolls
        "unlocks" -> MetricUnlocks
        else      -> MetricTime
    }

    val activeChart = when (metricTab) {
        "scrolls" -> scrollChart
        "unlocks" -> unlockChart
        else      -> screenTimeChart
    }

    // ── Historical insights for a tapped raw-metric bar ───────────────────────
    // Fetched lazily only when a past bar is tapped.
    // Today's bar always uses live ViewModel state — no fetch needed.
    val sdfDate  = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val todayStr = remember { sdfDate.format(Date()) }

    val chartSelectedDate = remember(chartSelectedIndex, activeChart, todayStr) {
        chartSelectedIndex
            ?.let { activeChart.getOrNull(it)?.date }
            ?.takeIf { it != todayStr }
    }

    var historicalInsights by remember { mutableStateOf<ScrollViewModel.HistoricalInsights?>(null) }
    LaunchedEffect(chartSelectedDate) {
        // Runs on Main dispatcher; fetchHistoricalInsights uses withContext(IO) internally
        historicalInsights = chartSelectedDate?.let { vm.fetchHistoricalInsights(it) }
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(24.dp))

        ScreenHeader(onSettingsClick = onSettingsClick)

        Spacer(Modifier.height(20.dp))

        // ── Time range selector ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Slate800, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TimeChip("7 Days",   selectedRange == 7,  Modifier.weight(1f)) { vm.setTimeRange(7)  }
            TimeChip("30 Days",  selectedRange == 30, Modifier.weight(1f)) { vm.setTimeRange(30) }
            TimeChip("3 Months", selectedRange == 90, Modifier.weight(1f)) { vm.setTimeRange(90) }
        }

        Spacer(Modifier.height(24.dp))

        // ════════════════════════════════════════════════════════════════════
        // PRIMARY — Wellness Score Trend
        // ════════════════════════════════════════════════════════════════════

        SectionLabel("WELLNESS TREND")
        Spacer(Modifier.height(12.dp))

        val validHistory = wellnessHistory.filter { it.score >= 0 }
        if (validHistory.size >= 2) {

            WellnessTrendChart(
                history       = wellnessHistory,
                selectedRange = selectedRange,
                selectedIndex = wellnessSelectedIdx,
                onSelect      = { wellnessSelectedIdx = it }
            )

            // When a bar is tapped, show that day's sub-metric breakdown inline
            val selectedPoint = wellnessSelectedIdx?.let { wellnessHistory.getOrNull(it) }
            if (selectedPoint != null && selectedPoint.score >= 0) {
                Spacer(Modifier.height(16.dp))
                WellnessDayBreakdown(point = selectedPoint)
            }

        } else {
            EmptyHistoryCard(
                icon    = "📈",
                message = "Wellness trend appears after 2+ days of data"
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Slate800)
        Spacer(Modifier.height(24.dp))

        // ════════════════════════════════════════════════════════════════════
        // PRIMARY — Time Hygiene Trend
        // ════════════════════════════════════════════════════════════════════

        SectionLabel("TIME HYGIENE TREND")
        Spacer(Modifier.height(12.dp))

        if (validHistory.size >= 2) {

            SubScoreTrendChart(
                history       = wellnessHistory,
                selectedRange = selectedRange,
                selectedIndex = hygieneSelectedIdx,
                onSelect      = { hygieneSelectedIdx = it },
                valueOf       = { it.timeHygiene },
                maxScore      = 20,
                tierOf        = { WellnessTier.from(it * 100 / 20) }
            )

            val selectedHygienePoint = hygieneSelectedIdx?.let { wellnessHistory.getOrNull(it) }
            if (selectedHygienePoint != null && selectedHygienePoint.timeHygiene >= 0) {
                Spacer(Modifier.height(16.dp))
                TimeHygieneDayBreakdown(point = selectedHygienePoint)
            }

        } else {
            EmptyHistoryCard(
                icon    = "🌙",
                message = "Time Hygiene trend appears after 2+ days of data"
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Slate800)
        Spacer(Modifier.height(24.dp))

        // ════════════════════════════════════════════════════════════════════
        // SECONDARY — Raw Metrics (Scrolls / Unlocks / Time)
        // ════════════════════════════════════════════════════════════════════

        SectionLabel("RAW METRICS")
        Spacer(Modifier.height(12.dp))

        // Tab toggle
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            TabButton("Scrolls", metricTab == "scrolls", MetricScrolls, Modifier.weight(1f)) { metricTab = "scrolls" }
            TabButton("Unlocks", metricTab == "unlocks", MetricUnlocks, Modifier.weight(1f)) { metricTab = "unlocks" }
            TabButton("Time",    metricTab == "time",    MetricTime,    Modifier.weight(1f)) { metricTab = "time"    }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Slate800))

        Spacer(Modifier.height(16.dp))

        if (activeChart.isNotEmpty()) {
            BarChart(
                data           = activeChart,
                timeRange      = selectedRange,
                accentColor    = accentColor,
                label          = when (metricTab) {
                    "scrolls" -> "Scrolls"
                    "unlocks" -> "Unlocks"
                    else      -> "Screen Time"
                },
                selectedIndex  = chartSelectedIndex,
                onSelect       = { chartSelectedIndex = it },
                valueFormatter = if (metricTab == "time") ::formatTotalMinutes else { v -> v.toString() },
                minMaxCount    = if (metricTab == "time") 540 else 1
            )
        } else {
            EmptyHistoryCard(icon = "📊", message = "No data yet for this period")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Slate800)
        Spacer(Modifier.height(16.dp))

        // ── Contextual insight cards ──────────────────────────────────────────
        // hi is non-null only when a past bar is tapped — live values used otherwise
        val hi = historicalInsights
        when (metricTab) {
            "scrolls" -> ScrollInsights(
                peakHour  = hi?.scrollPeakHour ?: peakScrollHour,
                breakdown = hi?.appBreakdown   ?: appBreakdown
            )
            "unlocks" -> UnlockInsights(
                firstMs  = hi?.unlockDay?.firstUnlockMs      ?: firstUnlockMs,
                lastMs   = hi?.unlockDay?.lastUnlockMs       ?: lastUnlockMs,
                avgMin   = hi?.unlockDay?.avgSessionMin      ?: avgSessionMin,
                longest  = hi?.unlockDay?.longestSessionMin  ?: longestSessionMin,
                peakHour = hi?.unlockPeakHour                ?: peakUnlockHour
            )
            else -> ScreenTimeInsights(
                avgMin    = hi?.unlockDay?.avgSessionMin     ?: avgSessionMin,
                longest   = hi?.unlockDay?.longestSessionMin ?: longestSessionMin,
                count     = hi?.unlockDay?.count             ?: unlockCount,
                totalFromHi = hi?.let {
                    ((it.unlockDay?.avgSessionMin ?: 0f) * (it.unlockDay?.count ?: 0)).toInt()
                },
                liveTotalMin = screenTimeMin
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Wellness day sub-metric breakdown (shown when a trend bar is tapped) ──────

@Composable
private fun WellnessDayBreakdown(point: WellnessHistoryPoint) {
    val score     = WellnessScore(
        total            = point.score,
        tier             = WellnessTier.from(point.score),
        scrollVolume     = point.scrollVolume.coerceAtLeast(0),
        sessionBehaviour = point.sessionBehaviour.coerceAtLeast(0),
        unlockFrequency  = point.unlockFrequency.coerceAtLeast(0),
        timeHygiene      = point.timeHygiene.coerceAtLeast(0),
        appQuality       = point.appQuality.coerceAtLeast(0),
        flaggedApps      = emptyList(),
        todayScrolls     = 0,
        baselineScrolls  = 0
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("BREAKDOWN")
        Spacer(Modifier.height(10.dp))
        ScoreComponent("Scroll Volume",    score.scrollVolume,     30)
        ScoreComponent("Session Length",   score.sessionBehaviour, 20)
        ScoreComponent("Unlock Frequency", score.unlockFrequency,  15)
        ScoreComponent("Time Hygiene",     score.timeHygiene,      20)
        ScoreComponent("App Quality",      score.appQuality,       15)
    }
}

// ── Time Hygiene sub-component breakdown (shown when a hygiene trend bar is tapped) ──

@Composable
private fun TimeHygieneDayBreakdown(point: WellnessHistoryPoint) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("BREAKDOWN")
        Spacer(Modifier.height(10.dp))
        if (point.bedtimeScore >= 0) {
            ScoreComponent("Bedtime", point.bedtimeScore, 10,
                subLabel = "How late your phone use ran into the night")
            ScoreComponent("Sleep Gap", point.gapScore, 6,
                subLabel = "Rest between last night's and this morning's first unlock")
            ScoreComponent("Consistency", point.consistencyScore, 4,
                subLabel = "How close to your usual wake time")
        } else {
            ScoreComponent("Time Hygiene", point.timeHygiene, 20,
                subLabel = "Detailed breakdown isn't available for this day")
        }
    }
}

// ── Per-metric insight card groups ────────────────────────────────────────────

@Composable
private fun ScrollInsights(peakHour: String, breakdown: List<Pair<String, Int>>) {
    InsightCard(Modifier.fillMaxWidth(), "Peak Hour", peakHour, MetricScrolls)
    if (breakdown.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Slate800)
        Spacer(Modifier.height(16.dp))
        AppBreakdownSection(entries = breakdown)
    }
}

@Composable
private fun UnlockInsights(
    firstMs: Long, lastMs: Long, avgMin: Float, longest: Int, peakHour: String
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InsightCard(Modifier.weight(1f), "First Unlock", formatTime(firstMs), MetricUnlocks)
        InsightCard(Modifier.weight(1f), "Last Unlock",  formatTime(lastMs),
            if (isLateNight(lastMs)) Red else MetricUnlocks)
    }
    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InsightCard(Modifier.weight(1f), "Avg Session",     formatMinutes(avgMin),
            MaterialTheme.colorScheme.onBackground)
        InsightCard(Modifier.weight(1f), "Longest Session", "${longest}m",
            if (longest > 20) Red else MaterialTheme.colorScheme.onBackground)
    }
    Spacer(Modifier.height(10.dp))
    InsightCard(Modifier.fillMaxWidth(), "Peak Hour", peakHour, MetricUnlocks)
}

@Composable
private fun ScreenTimeInsights(
    avgMin: Float, longest: Int, count: Int,
    totalFromHi: Int?, liveTotalMin: Int
) {
    val displayTotal = totalFromHi ?: liveTotalMin
    InsightCard(
        Modifier.fillMaxWidth(), "Total Screen Time", formatTotalMinutes(displayTotal),
        if (displayTotal > 120) Red else MetricTime
    )
    Spacer(Modifier.height(10.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InsightCard(Modifier.weight(1f), "Avg Session",     formatMinutes(avgMin), MetricTime)
        InsightCard(Modifier.weight(1f), "Longest Session", formatMinutes(longest.toFloat()),
            if (longest > 30) Red else MetricTime)
    }
}

// ── Small helpers ─────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style         = MaterialTheme.typography.labelSmall,
        color         = Slate400,
        fontSize      = 10.sp,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun EmptyHistoryCard(icon: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Slate800)
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(icon, fontSize = 32.sp)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = Slate500, textAlign = TextAlign.Center)
        }
    }
}
