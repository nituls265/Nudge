package com.example.nudgev0

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nudgev0.data.ScrollDay
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.delay

// ── Colours ───────────────────────────────────────────────────────────────────

private val Green  = Color(0xFF34D399)
private val Blue   = Color(0xFF60A5FA)
private val Red    = Color(0xFFEF4444)
private val Slate800 = Color(0xFF1E293B)
private val Slate700 = Color(0xFF334155)
private val Slate400 = Color(0xFF94A3B8)
private val Slate500 = Color(0xFF64748B)

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(factory: ScrollViewModelFactory) {
    val vm: ScrollViewModel = viewModel(factory = factory)
    val context = LocalContext.current

    // Scroll
    val scrollCount    by vm.scrollCount.collectAsState()
    val scrollChart    by vm.scrollChartData.collectAsState()
    val peakScrollHour by vm.peakScrollHour.collectAsState()

    // Unlock
    val unlockCount       by vm.unlockCount.collectAsState()
    val unlockChart       by vm.unlockChartData.collectAsState()
    val peakUnlockHour    by vm.peakUnlockHour.collectAsState()
    val firstUnlockMs     by vm.firstUnlockMs.collectAsState()
    val lastUnlockMs      by vm.lastUnlockMs.collectAsState()
    val avgSessionMin     by vm.avgSessionMin.collectAsState()
    val longestSessionMin by vm.longestSessionMin.collectAsState()

    // App breakdown
    val appBreakdown by vm.appBreakdown.collectAsState()

    // Laptop sync
    val syncCode        by vm.syncCode.collectAsState()
    val laptopCount     by vm.laptopCount.collectAsState()
    val totalScrollCount by vm.totalScrollCount.collectAsState()

    // UI state
    val isBubbleVisible by vm.isBubbleVisible.collectAsState()
    val isPaused        by vm.isPaused.collectAsState()
    val selectedRange   by vm.timeRange.collectAsState()
    var selectedTab     by remember { mutableStateOf("scrolls") }

    val calibrationDaysRemaining = remember {
        val prefs = context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
        val first = prefs.getLong("FIRST_LAUNCH_DATE", System.currentTimeMillis())
        maxOf(0, 7 - TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - first).toInt())
    }
    val isCalibrating = calibrationDaysRemaining > 0
    val permissionsGranted = Settings.canDrawOverlays(context) &&
            isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)

    // Derived
    val activeChart = if (selectedTab == "scrolls") scrollChart else unlockChart
    val accentColor = if (selectedTab == "scrolls") Green else Blue

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Nudge",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()),
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate500
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── 2 Metric Cards ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier    = Modifier.weight(1f),
                    label       = "Scrolls Today",
                    value       = totalScrollCount.toString(),
                    deltaText   = if (laptopCount > 0)
                        "📱 $scrollCount  +  💻 $laptopCount"
                    else
                        scrollChart.avgDeltaLabel(scrollCount),
                    deltaUp     = scrollChart.isAboveAvg(totalScrollCount),
                    accentColor = Green,
                    isActive    = selectedTab == "scrolls",
                    onClick     = { selectedTab = "scrolls" }
                )
                MetricCard(
                    modifier    = Modifier.weight(1f),
                    label       = "Unlocks Today",
                    value       = unlockCount.toString(),
                    deltaText   = unlockChart.avgDeltaLabel(unlockCount),
                    deltaUp     = unlockChart.isAboveAvg(unlockCount),
                    accentColor = Blue,
                    isActive    = selectedTab == "unlocks",
                    onClick     = { selectedTab = "unlocks" }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Time Range Selector ───────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate800, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TimeChip("7 Days",    selectedRange == 7,  Modifier.weight(1f)) { vm.setTimeRange(7) }
                TimeChip("30 Days",   selectedRange == 30, Modifier.weight(1f)) { vm.setTimeRange(30) }
                TimeChip("3 Months",  selectedRange == 90, Modifier.weight(1f)) { vm.setTimeRange(90) }
            }

            Spacer(Modifier.height(20.dp))

            // ── Tab Toggle ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                TabButton(
                    label    = "Scrolls",
                    isActive = selectedTab == "scrolls",
                    color    = Green,
                    modifier = Modifier.weight(1f),
                    onClick  = { selectedTab = "scrolls" }
                )
                TabButton(
                    label    = "Unlocks",
                    isActive = selectedTab == "unlocks",
                    color    = Blue,
                    modifier = Modifier.weight(1f),
                    onClick  = { selectedTab = "unlocks" }
                )
            }

            // Tab underline
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Slate800)
            )

            Spacer(Modifier.height(16.dp))

            // ── Bar Chart ────────────────────────────────────────────────────
            if (activeChart.isNotEmpty()) {
                BarChart(
                    data        = activeChart,
                    timeRange   = selectedRange,
                    accentColor = accentColor,
                    label       = if (selectedTab == "scrolls") "Scrolls" else "Unlocks"
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Slate800)
            Spacer(Modifier.height(16.dp))

            // ── Contextual Insight Cards ──────────────────────────────────────
            if (selectedTab == "scrolls") {
                InsightCard(
                    modifier = Modifier.fillMaxWidth(),
                    label    = "Peak Hour",
                    value    = peakScrollHour,
                    color    = Green
                )
                if (appBreakdown.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = Slate800)
                    Spacer(Modifier.height(16.dp))
                    AppBreakdownSection(entries = appBreakdown)
                }
            } else {
                // First / Last unlock
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        label    = "First Unlock",
                        value    = formatTime(firstUnlockMs),
                        color    = Blue
                    )
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        label    = "Last Unlock",
                        value    = formatTime(lastUnlockMs),
                        color    = if (isLateNight(lastUnlockMs)) Red else Blue
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Avg Session / Longest Session
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        label    = "Avg Session",
                        value    = formatMinutes(avgSessionMin),
                        color    = MaterialTheme.colorScheme.onBackground
                    )
                    InsightCard(
                        modifier = Modifier.weight(1f),
                        label    = "Longest Session",
                        value    = "${longestSessionMin} min",
                        color    = if (longestSessionMin > 20) Red else MaterialTheme.colorScheme.onBackground
                    )
                }

                Spacer(Modifier.height(10.dp))

                // Peak hour — full width
                InsightCard(
                    modifier = Modifier.fillMaxWidth(),
                    label    = "Peak Hour",
                    value    = peakUnlockHour,
                    color    = Blue
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Slate800)
            Spacer(Modifier.height(24.dp))

            // ── Calibration Card ──────────────────────────────────────────────
            if (isCalibrating) {
                CalibrationCard(daysRemaining = calibrationDaysRemaining)
                Spacer(Modifier.height(16.dp))
            }

            // ── Controls ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = {
                            when {
                                !Settings.canDrawOverlays(context) ->
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                !isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java) ->
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                else -> vm.toggleBubble()
                            }
                        },
                        enabled = !isCalibrating || !permissionsGranted,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        Text(if (isBubbleVisible) "Hide Bubble" else "Show Bubble")
                    }
                    if (isCalibrating && permissionsGranted) LockBadge(Modifier.align(Alignment.TopEnd))
                }
                Box(modifier = Modifier.weight(1f)) {
                    FilledTonalButton(
                        onClick  = { vm.togglePause() },
                        enabled  = !isCalibrating,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        Text(
                            if (isPaused) "Resume" else "Pause",
                            color = if (isCalibrating)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else MaterialTheme.colorScheme.onBackground
                        )
                    }
                    if (isCalibrating) LockBadge(Modifier.align(Alignment.TopEnd))
                }
            }

            Spacer(Modifier.height(16.dp))

            LaptopSyncCard(syncCode = syncCode, context = context)

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { vm.resetScrollCount() },
                enabled = !isCalibrating,
                colors  = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
                )
            ) {
                Text("Reset Today's Data", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Components ────────────────────────────────────────────────────────────────

@Composable
private fun MetricCard(
    modifier: Modifier,
    label: String,
    value: String,
    deltaText: String,
    deltaUp: Boolean,
    accentColor: Color,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape    = RoundedCornerShape(22.dp),
        colors   = CardDefaults.cardColors(containerColor = Slate800),
        border   = if (isActive) androidx.compose.foundation.BorderStroke(2.dp, accentColor.copy(alpha = 0.3f)) else null
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(accentColor)
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Slate400,
                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold, color = accentColor,
                letterSpacing = (-1).sp)
            Spacer(Modifier.height(4.dp))
            Text(
                deltaText,
                style = MaterialTheme.typography.labelSmall,
                color = if (deltaUp) Red else Green,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun InsightCard(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                label.uppercase(),
                style    = MaterialTheme.typography.labelSmall,
                color    = Slate500,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun TabButton(
    label: String,
    isActive: Boolean,
    color: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val textColor = if (isActive) color else Slate500
    Box(
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = textColor)
    }
    // The underline is drawn as a separate Box below the Row in MainScreen
}

@Composable
fun CalibrationCard(daysRemaining: Int) {
    val currentDay = 8 - daysRemaining
    val progress = (currentDay - 1) / 7f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("📊", fontSize = 13.sp) }
                Text("Calibration Mode", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Nudge is learning your natural habits. Controls unlock once we have your 7-day baseline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Day $currentDay of 7", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text("$daysRemaining left", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun LockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .offset(x = 6.dp, y = (-6).dp)
            .size(20.dp)
            .background(MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text("🔒", fontSize = 9.sp) }
}

@Composable
private fun LaptopSyncCard(syncCode: String, context: Context) {
    val Teal = Color(0xFF2DD4BF)
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) { delay(1500L); copied = false }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Slate800)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("💻", fontSize = 16.sp)
                Text(
                    "Chrome Extension Sync",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste this code in the Nudge Chrome extension to sync laptop scrolls.",
                style      = MaterialTheme.typography.bodySmall,
                color      = Slate500,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Show sync code in groups of 4 for readability
                Text(
                    if (syncCode == "—") "Signing in…"
                    else syncCode.chunked(4).joinToString("  "),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color      = Teal
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Teal.copy(alpha = 0.15f))
                        .clickable {
                            if (syncCode != "—") {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Nudge Sync Code", syncCode))
                                copied = true
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (copied) "Copied ✓" else "Copy",
                        style      = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color      = if (copied) Green else Teal
                    )
                }
            }
        }
    }
}

@Composable
fun TimeChip(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val tc = if (isSelected) MaterialTheme.colorScheme.onPrimary else Slate400
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = tc, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center)
    }
}

// ── Bar Chart ────────────────────────────────────────────────────────────────

@Composable
private fun BarChart(
    data: List<ScrollDay>,
    timeRange: Int,
    accentColor: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val selectedState = remember(timeRange) { mutableStateOf<Int?>(null) }
    var selectedIndex by selectedState
    val context = LocalContext.current

    // Show hint on non-7-day views for first 3 app launches
    val hintEligible = remember(timeRange) {
        timeRange != 7 && context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .getInt("CHART_HINT_COUNT", 0) < 3
    }
    var hintVisible by remember(timeRange) { mutableStateOf(hintEligible) }
    LaunchedEffect(timeRange) {
        if (hintEligible) { delay(2500L); hintVisible = false }
    }

    val maxCount  = data.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    val avg       = remember(data) { data.map { it.count }.average().toInt() }
    val dfParse   = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dfDisplay = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    // ── Dynamic header text ───────────────────────────────────────────────────
    val (headerPrimary, headerSecondary) = when (val sel = selectedIndex) {
        null -> {
            val suffix = if (timeRange == 90) "/ Week (avg)" else "/ Day"
            Pair("$label $suffix", "Avg: $avg/day")
        }
        else -> when (timeRange) {
            90 -> {
                val week  = data[sel]
                val start = runCatching { dfParse.parse(week.date) }.getOrNull()
                val end   = start?.let { Date(it.time + 6L * 86_400_000) }
                val range = if (start != null && end != null)
                    "${dfDisplay.format(start)} – ${dfDisplay.format(end)}" else week.date
                Pair("Week of $range", "Avg: ${week.count}/day")
            }
            else -> {
                val day  = data[sel]
                val date = runCatching { dfParse.parse(day.date) }.getOrNull()
                Pair(date?.let { dfDisplay.format(it) } ?: day.date, "${day.count} $label")
            }
        }
    }

    Column(modifier = modifier) {
        // ── Header + swipe-to-navigate ────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var handled = false
                    detectHorizontalDragGestures(
                        onDragStart = { handled = false },
                        onHorizontalDrag = { _, dragAmount ->
                            if (!handled && abs(dragAmount) > 40f) {
                                handled = true
                                val cur = selectedState.value ?: return@detectHorizontalDragGestures
                                selectedState.value = if (dragAmount < 0)
                                    (cur + 1).coerceAtMost(data.lastIndex)
                                else
                                    (cur - 1).coerceAtLeast(0)
                            }
                        }
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    headerPrimary,
                    style      = MaterialTheme.typography.labelLarge,
                    color      = if (selectedIndex != null) accentColor else Slate400,
                    fontWeight = if (selectedIndex != null) FontWeight.Bold else FontWeight.Normal
                )
                Text(headerSecondary, style = MaterialTheme.typography.labelMedium, color = Slate500)
            }
            if (selectedIndex != null) {
                Text(
                    "✕",
                    color    = Slate500,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { selectedIndex = null }
                )
            }
        }

        // ── Hint ─────────────────────────────────────────────────────────────
        AnimatedVisibility(visible = hintVisible, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Tap a bar to explore",
                style    = MaterialTheme.typography.labelSmall,
                color    = Slate500,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Chart area — outer Box deselects on tap outside a bar ────────────
        val barGap = if (timeRange == 7) 8.dp else if (timeRange == 30) 6.dp else 4.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { if (selectedIndex != null) selectedIndex = null }
        ) {
            if (timeRange == 90) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(barGap),
                    verticalAlignment = Alignment.Bottom
                ) {
                    data.forEachIndexed { index, day ->
                        BarColumn(
                            day          = day,
                            index        = index,
                            timeRange    = timeRange,
                            isToday      = false,
                            selectedIndex = selectedIndex,
                            accentColor  = accentColor,
                            maxCount     = maxCount,
                            isScrollsTab = label.equals("Scrolls", ignoreCase = true),
                            modifier     = Modifier.weight(1f),
                            onClick      = { selectedIndex = if (selectedIndex == index) null else index }
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(barGap),
                    verticalAlignment = Alignment.Bottom
                ) {
                    data.forEachIndexed { index, day ->
                        BarColumn(
                            day          = day,
                            index        = index,
                            timeRange    = timeRange,
                            isToday      = index == data.lastIndex,
                            selectedIndex = selectedIndex,
                            accentColor  = accentColor,
                            maxCount     = maxCount,
                            isScrollsTab = label.equals("Scrolls", ignoreCase = true),
                            modifier     = Modifier.width(28.dp),
                            onClick      = if (timeRange == 7) ({}) else
                                          ({ selectedIndex = if (selectedIndex == index) null else index })
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BarColumn(
    day: ScrollDay,
    index: Int,
    timeRange: Int,
    isToday: Boolean,
    selectedIndex: Int?,
    accentColor: Color,
    maxCount: Int,
    isScrollsTab: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isSelected   = selectedIndex == index
    val hasSelection = selectedIndex != null

    // Today anchors at full opacity when nothing selected; yields when selection active
    val alpha = when {
        !hasSelection -> if (isToday) 1f else 0.4f
        isSelected    -> 1f
        else          -> 0.4f
    }

    val ratio    = day.count.toFloat() / maxCount
    val barH     = if (day.count == 0) 0.dp else (140 * ratio).dp.coerceAtLeast(4.dp)
    val isHigh   = isScrollsTab && day.count > 400
    val barColor = (if (isHigh) Red else accentColor).copy(alpha = alpha)

    val dayLabel = when (timeRange) {
        90   -> if (index % 2 == 0) "W${index + 1}" else ""
        30   -> if (index % 5 == 0) "${index + 1}" else ""
        else -> day.date.takeLast(2)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                indication        = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        if (timeRange == 7 && day.count > 0) {
            Text(
                day.count.toString(),
                fontSize   = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (isToday) MaterialTheme.colorScheme.onBackground else Slate400
            )
            Spacer(Modifier.height(4.dp))
        }
        Box(
            modifier = Modifier
                .then(if (timeRange == 90) Modifier.fillMaxWidth(0.7f) else Modifier.width(28.dp))
                .height(barH)
                .background(barColor, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            dayLabel,
            fontSize   = 9.sp,
            fontWeight = FontWeight.Medium,
            color      = when {
                isSelected -> accentColor.copy(alpha = 0.8f)
                isToday    -> Slate400
                else       -> Slate500
            }
        )
    }
}

// ── App Breakdown ─────────────────────────────────────────────────────────────

@Composable
private fun AppBreakdownSection(entries: List<Pair<String, Int>>) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val total   = entries.sumOf { it.second }.coerceAtLeast(1)
    val maxCount = entries.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
    val shown   = if (expanded) entries else entries.take(3)
    val hidden  = entries.size - 3

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "APP BREAKDOWN",
            style = MaterialTheme.typography.labelSmall,
            color = Slate400,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        if (entries.size > 3) {
            Text(
                text = if (expanded) "Show less ▲" else "+$hidden more ▼",
                style = MaterialTheme.typography.labelSmall,
                color = Green,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    shown.forEach { (pkg, count) ->
        val appName = remember(pkg) { resolveAppName(context.packageManager, pkg) }
        val pct     = (count.toFloat() / total * 100).toInt()
        val fillPct = count.toFloat() / maxCount

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        appName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$pct%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Green,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .background(Slate800, RoundedCornerShape(3.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillPct)
                            .fillMaxHeight()
                            .background(
                                if (pkg == "unknown") Slate700 else Green,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
        }
    }
}

private fun resolveAppName(pm: PackageManager, packageName: String): String {
    if (packageName == "unknown" || packageName.isEmpty()) return "Other"
    return try {
        val info = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName.substringAfterLast('.')
            .replaceFirstChar { it.uppercase() }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatTime(ms: Long): String {
    if (ms == 0L) return "–"
    val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    return fmt.format(Date(ms))
}

private fun formatMinutes(min: Float): String {
    if (min < 1f) return "< 1 min"
    return "${min.toInt()} min"
}

private fun isLateNight(ms: Long): Boolean {
    if (ms == 0L) return false
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    return hour >= 23 || hour < 5
}

private fun List<ScrollDay>.avgDeltaLabel(today: Int): String {
    if (isEmpty()) return "No baseline yet"
    val avg = map { it.count }.average()
    if (avg == 0.0) return "No baseline yet"
    val pct = ((today - avg) / avg * 100).toInt()
    return if (pct >= 0) "↑ $pct% vs avg" else "↓ ${-pct}% vs avg"
}

private fun List<ScrollDay>.isAboveAvg(today: Int): Boolean {
    if (isEmpty()) return false
    return today > map { it.count }.average()
}
