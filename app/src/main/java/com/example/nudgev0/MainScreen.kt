package com.example.nudgev0

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nudgev0.data.ScrollDay
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

// ── Shared colour tokens ──────────────────────────────────────────────────────
// Not private so HomeTab / HistoryTab / SettingsSheet (same package) can use them.

val Green   = Color(0xFF34D399)
val Blue    = Color(0xFF60A5FA)
val Purple  = Color(0xFFA78BFA)
val Red     = Color(0xFFEF4444)
val Slate900 = Color(0xFF0F172A)
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155)
val Slate500 = Color(0xFF64748B)
val Slate400 = Color(0xFF94A3B8)

// ── Navigation ────────────────────────────────────────────────────────────────

enum class AppTab { HOME, HISTORY }

// ── Root composable (entry-point from MainActivity) ───────────────────────────

@Composable
fun MainScreen(factory: ScrollViewModelFactory) {
    val vm: ScrollViewModel = viewModel(factory = factory)
    var showSettings by remember { mutableStateOf(false) }

    // Pager drives both swipe navigation and the bottom nav indicator.
    // pageCount = 2: page 0 = Home, page 1 = History.
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope      = rememberCoroutineScope()

    // Derive the selected tab from the pager so the indicator always tracks
    // mid-swipe, not just on settle.
    val selectedTab = if (pagerState.currentPage == 0) AppTab.HOME else AppTab.HISTORY

    Scaffold(
        modifier       = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar      = {
            BottomNavBar(
                selectedTab  = selectedTab,
                settleOffset = pagerState.currentPageOffsetFraction,
                onSelect     = { tab ->
                    scope.launch {
                        pagerState.animateScrollToPage(
                            if (tab == AppTab.HOME) 0 else 1
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state    = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            // Disable over-scroll glow — it conflicts with charts that have
            // their own horizontal scroll inside the History tab.
            beyondViewportPageCount = 0
        ) { page ->
            when (page) {
                0    -> HomeTab(vm = vm,    onSettingsClick = { showSettings = true })
                else -> HistoryTab(vm = vm, onSettingsClick = { showSettings = true })
            }
        }
    }

    if (showSettings) {
        SettingsSheet(vm = vm, onDismiss = { showSettings = false })
    }
}

// ── Bottom nav bar ────────────────────────────────────────────────────────────

@Composable
internal fun BottomNavBar(
    selectedTab: AppTab,
    settleOffset: Float,        // pagerState.currentPageOffsetFraction for live tracking
    onSelect: (AppTab) -> Unit
) {
    Surface(
        color           = Slate900,
        shadowElevation = 8.dp,
        modifier        = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(62.dp)
        ) {
            NavTabItem(
                icon     = "🏠",
                label    = "Home",
                selected = selectedTab == AppTab.HOME,
                modifier = Modifier.weight(1f),
                onClick  = { onSelect(AppTab.HOME) }
            )
            NavTabItem(
                icon     = "📊",
                label    = "History",
                selected = selectedTab == AppTab.HISTORY,
                modifier = Modifier.weight(1f),
                onClick  = { onSelect(AppTab.HISTORY) }
            )
        }
    }
}

@Composable
private fun NavTabItem(
    icon: String, label: String, selected: Boolean,
    modifier: Modifier, onClick: () -> Unit
) {
    val color = if (selected) Green else Slate500

    // The outer Box carries weight(1f) — correct inside a Row.
    // The clickable wraps the ENTIRE cell so any tap anywhere in the tab works.
    // The inner Column uses Modifier.fillMaxSize() (not the outer modifier) so
    // weight isn't re-applied inside a Box (it has no effect there and caused
    // the content to collapse to the top-left corner).
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                indication        = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center          // centres icon+label in the cell
    ) {
        // Selected indicator — 2 dp line pinned to the top of the cell
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Green)
            )
        }

        // Icon + label — centred by the Box's contentAlignment
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                icon,
                fontSize = if (selected) 20.sp else 18.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style      = MaterialTheme.typography.labelSmall,
                color      = color,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize   = 10.sp
            )
        }
    }
}

// ── Shared screen header (used by both tabs) ──────────────────────────────────

@Composable
internal fun ScreenHeader(onSettingsClick: () -> Unit) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "nudge",
            style      = MaterialTheme.typography.titleSmall,
            color      = Slate400,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date()),
            style = MaterialTheme.typography.bodySmall,
            color = Slate500
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Slate800)
                .clickable(
                    indication        = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onSettingsClick() },
            contentAlignment = Alignment.Center
        ) { Text("⚙️", fontSize = 13.sp) }
    }
}

// ── InsightCard ───────────────────────────────────────────────────────────────

@Composable
internal fun InsightCard(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Slate800.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                label.uppercase(),
                style         = MaterialTheme.typography.labelSmall,
                color         = Slate500,
                fontSize      = 10.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = color)
        }
    }
}

// ── TabButton ────────────────────────────────────────────────────────────────

@Composable
internal fun TabButton(
    label: String, isActive: Boolean, color: Color,
    modifier: Modifier, onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color      = if (isActive) color else Slate500
        )
    }
}

// ── CalibrationCard ───────────────────────────────────────────────────────────

@Composable
fun CalibrationCard(daysRemaining: Int) {
    val currentDay = 8 - daysRemaining
    val progress   = (currentDay - 1) / 7f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border   = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(
            modifier             = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement  = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier         = Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("📊", fontSize = 13.sp) }
                Text(
                    "Calibration Mode",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Nudge is learning your natural habits. Controls unlock once we have your 7-day baseline.",
                style      = MaterialTheme.typography.bodySmall,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Day $currentDay of 7",
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    progress          = { progress },
                    modifier          = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color             = MaterialTheme.colorScheme.primary,
                    trackColor        = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    "$daysRemaining left",
                    style      = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── LockBadge ─────────────────────────────────────────────────────────────────

@Composable
fun LockBadge(modifier: Modifier = Modifier) {
    Box(
        modifier         = modifier
            .offset(x = 6.dp, y = (-6).dp)
            .size(20.dp)
            .background(MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text("🔒", fontSize = 9.sp) }
}

// ── LaptopSyncCard ────────────────────────────────────────────────────────────

@Composable
internal fun LaptopSyncCard(syncCode: String, context: Context) {
    val Teal   = Color(0xFF2DD4BF)
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
                verticalAlignment     = Alignment.CenterVertically,
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
                    .background(Slate900, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (syncCode == "—") "Signing in…"
                    else syncCode.chunked(4).joinToString("  "),
                    style         = MaterialTheme.typography.titleMedium,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color         = Teal
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

// ── TimeChip ──────────────────────────────────────────────────────────────────

@Composable
fun TimeChip(
    text: String, isSelected: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
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
        Text(
            text, color = tc,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            textAlign  = TextAlign.Center
        )
    }
}

// ── BarChart ──────────────────────────────────────────────────────────────────

@Composable
internal fun BarChart(
    data: List<ScrollDay>,
    timeRange: Int,
    accentColor: Color,
    label: String,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    valueFormatter: (Int) -> String = { it.toString() },
    minMaxCount: Int = 1
) {
    if (data.isEmpty()) return

    val context      = LocalContext.current
    val hintEligible = remember(timeRange) {
        timeRange != 7 && context
            .getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .getInt("CHART_HINT_COUNT", 0) < 3
    }
    var hintVisible by remember(timeRange) { mutableStateOf(hintEligible) }
    LaunchedEffect(timeRange) {
        if (hintEligible) { delay(2500L); hintVisible = false }
    }

    val maxCount  = data.maxOfOrNull { it.count }?.coerceAtLeast(minMaxCount) ?: minMaxCount
    val avg       = remember(data) { data.map { it.count }.average().toInt() }
    val dfParse   = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dfDisplay = remember { SimpleDateFormat("MMM d",      Locale.getDefault()) }

    val (headerPrimary, headerSecondary) = when (val sel = selectedIndex) {
        null -> {
            val suffix = if (timeRange == 90) "/ Week (avg)" else "/ Day"
            Pair("$label $suffix", "Avg: ${valueFormatter(avg)}/day")
        }
        else -> when (timeRange) {
            90 -> {
                val week  = data[sel]
                val start = runCatching { dfParse.parse(week.date) }.getOrNull()
                val end   = start?.let { Date(it.time + 6L * 86_400_000) }
                val range = if (start != null && end != null)
                    "${dfDisplay.format(start)} – ${dfDisplay.format(end)}" else week.date
                Pair("Week of $range", "Avg: ${valueFormatter(week.count)}/day")
            }
            else -> {
                val day  = data[sel]
                val date = runCatching { dfParse.parse(day.date) }.getOrNull()
                Pair(date?.let { dfDisplay.format(it) } ?: day.date, "${valueFormatter(day.count)} $label")
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var handled = false
                    detectHorizontalDragGestures(
                        onDragStart      = { handled = false },
                        onHorizontalDrag = { _, dragAmount ->
                            if (!handled && abs(dragAmount) > 40f) {
                                handled = true
                                val cur = selectedIndex ?: return@detectHorizontalDragGestures
                                onSelect(
                                    if (dragAmount < 0) (cur + 1).coerceAtMost(data.lastIndex)
                                    else                (cur - 1).coerceAtLeast(0)
                                )
                            }
                        }
                    )
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Bottom
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
                            indication        = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { onSelect(null) }
                )
            }
        }

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

        val barGap = if (timeRange == 7) 8.dp else if (timeRange == 30) 6.dp else 4.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clickable(
                    indication        = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { if (selectedIndex != null) onSelect(null) }
        ) {
            if (timeRange == 90) {
                Row(
                    modifier              = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(barGap),
                    verticalAlignment     = Alignment.Bottom
                ) {
                    data.forEachIndexed { index, day ->
                        BarColumn(
                            day           = day,
                            index         = index,
                            timeRange     = timeRange,
                            isToday       = false,
                            selectedIndex = selectedIndex,
                            accentColor   = accentColor,
                            maxCount      = maxCount,
                            isScrollsTab  = label.equals("Scrolls", ignoreCase = true),
                            modifier      = Modifier.weight(1f),
                            onClick       = { onSelect(if (selectedIndex == index) null else index) }
                        )
                    }
                }
            } else {
                val hScrollState = rememberScrollState()
                LaunchedEffect(data) { hScrollState.scrollTo(hScrollState.maxValue) }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(hScrollState),
                    horizontalArrangement = Arrangement.spacedBy(barGap),
                    verticalAlignment     = Alignment.Bottom
                ) {
                    data.forEachIndexed { index, day ->
                        BarColumn(
                            day            = day,
                            index          = index,
                            timeRange      = timeRange,
                            isToday        = index == data.lastIndex,
                            selectedIndex  = selectedIndex,
                            accentColor    = accentColor,
                            maxCount       = maxCount,
                            isScrollsTab   = label.equals("Scrolls", ignoreCase = true),
                            valueFormatter = valueFormatter,
                            modifier       = Modifier.width(28.dp),
                            onClick        = { onSelect(if (selectedIndex == index) null else index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun BarColumn(
    day: ScrollDay,
    index: Int,
    timeRange: Int,
    isToday: Boolean,
    selectedIndex: Int?,
    accentColor: Color,
    maxCount: Int,
    isScrollsTab: Boolean,
    valueFormatter: (Int) -> String = { it.toString() },
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isSelected   = selectedIndex == index
    val hasSelection = selectedIndex != null
    val alpha        = when {
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
        modifier            = modifier
            .fillMaxHeight()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
    ) {
        if (timeRange == 7 && day.count > 0) {
            Text(valueFormatter(day.count), fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = if (isToday) MaterialTheme.colorScheme.onBackground else Slate400)
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
            dayLabel, fontSize = 9.sp, fontWeight = FontWeight.Medium,
            color = when {
                isSelected -> accentColor.copy(alpha = 0.8f)
                isToday    -> Slate400
                else       -> Slate500
            }
        )
    }
}

// ── AppBreakdownSection ───────────────────────────────────────────────────────

@Composable
internal fun AppBreakdownSection(entries: List<Pair<String, Int>>) {
    val context   = LocalContext.current
    var expanded  by remember { mutableStateOf(false) }
    val total     = entries.sumOf { it.second }.coerceAtLeast(1)
    val maxCount  = entries.firstOrNull()?.second?.coerceAtLeast(1) ?: 1
    val shown     = if (expanded) entries else entries.take(3)
    val hidden    = entries.size - 3

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text("SCROLL SOURCES", style = MaterialTheme.typography.labelSmall,
            color = Slate400, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        if (entries.size > 3) {
            Text(
                if (expanded) "Show less ▲" else "+$hidden more ▼",
                style      = MaterialTheme.typography.labelSmall,
                color      = Green,
                fontWeight = FontWeight.Bold,
                fontSize   = 11.sp,
                modifier   = Modifier.clickable { expanded = !expanded }
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    shown.forEach { (pkg, count) ->
        val appName = remember(pkg) { resolveAppName(context.packageManager, pkg) }
        val pct     = (count.toFloat() / total * 100).toInt()
        val fillPct = count.toFloat() / maxCount

        Row(
            modifier              = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(appName, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onBackground,
                        modifier   = Modifier.weight(1f))
                    Text("$count", style = MaterialTheme.typography.labelSmall,
                        color = Slate500, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("$pct%", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = Green, fontSize = 12.sp)
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(5.dp)
                        .background(Slate800, RoundedCornerShape(3.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fillPct)
                            .fillMaxHeight()
                            .background(
                                if (pkg == "unknown" || pkg == "other") Slate700 else Green,
                                RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
        }
    }
}

// ── ScoreComponent (sub-metric bar row) ───────────────────────────────────────

@Composable
internal fun ScoreComponent(
    label: String, points: Int, maxPts: Int, subLabel: String? = null
) {
    val fill    = (points.toFloat() / maxPts).coerceIn(0f, 1f)
    val animFill by animateFloatAsState(
        targetValue   = fill,
        animationSpec = tween(700),
        label         = "component_$label"
    )
    // Colour each driver by ITS OWN health, mapped onto the SAME 5-tier scale the
    // overall wellness score uses (🌿green ≥85 · ✨blue ≥70 · 🌊yellow ≥50 ·
    // ⚡orange ≥30 · 🔴red). Reusing WellnessTier keeps the colour language
    // identical to the score ring + tier-scale bar above, so a glance tells you
    // which drivers are strong and which are dragging the score down.
    val pct      = points * 100 / maxPts
    val barColor = Color(WellnessTier.from(pct).colorHex)
    val isFull   = points == maxPts

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Slate400,
                fontSize = 11.sp, modifier = Modifier.width(130.dp))
            Box(
                modifier = Modifier.weight(1f).height(5.dp)
                    .background(Slate900, RoundedCornerShape(3.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animFill)
                        .fillMaxHeight()
                        .background(barColor, RoundedCornerShape(3.dp))
                )
            }
            Text(
                "$points/$maxPts",
                style      = MaterialTheme.typography.labelSmall,
                color      = if (isFull) barColor else Slate500,
                fontSize   = 10.sp,
                fontWeight = if (isFull) FontWeight.Bold else FontWeight.Normal,
                modifier   = Modifier.width(32.dp),
                textAlign  = TextAlign.End
            )
        }
        if (subLabel != null) {
            Text(subLabel, style = MaterialTheme.typography.labelSmall,
                color = Slate500, fontSize = 9.sp,
                modifier = Modifier.padding(start = 140.dp, top = 2.dp))
        }
    }
}

// ── WellnessTrendChart ────────────────────────────────────────────────────────

@Composable
internal fun WellnessTrendChart(
    history: List<WellnessHistoryPoint>,
    selectedRange: Int,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit
) {
    if (history.isEmpty()) return

    val maxScore  = 90
    val dfParse   = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val dfDisplay = remember { SimpleDateFormat("MMM d",      Locale.getDefault()) }

    val headerLabel = selectedIndex?.let { idx ->
        history.getOrNull(idx)?.date?.let {
            runCatching { dfDisplay.format(dfParse.parse(it)!!) }.getOrNull()
        }
    }

    Column {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                if (headerLabel != null) headerLabel.uppercase() else "TREND",
                style         = MaterialTheme.typography.labelSmall,
                color         = Slate400,
                fontSize      = 10.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            if (selectedIndex != null) {
                Text("✕", color = Slate500, fontSize = 11.sp,
                    modifier = Modifier.clickable(
                        indication = null, interactionSource = remember { MutableInteractionSource() }
                    ) { onSelect(null) })
            }
        }
        Spacer(Modifier.height(8.dp))

        val barGap       = if (selectedRange == 7) 6.dp else if (selectedRange == 30) 4.dp else 3.dp
        val hasSelection = selectedIndex != null
        val chartH       = 80.dp

        val hScrollState = rememberScrollState()
        LaunchedEffect(history, selectedRange) { hScrollState.scrollTo(hScrollState.maxValue) }

        Row(
            modifier = if (selectedRange == 90) Modifier.fillMaxWidth()
                       else Modifier.horizontalScroll(hScrollState),
            horizontalArrangement = Arrangement.spacedBy(barGap),
            verticalAlignment     = Alignment.Bottom
        ) {
            history.forEachIndexed { index, point ->
                val isToday    = index == history.lastIndex
                val isSelected = index == selectedIndex
                val hasData    = point.score >= 0
                val tier       = if (hasData) WellnessTier.from(point.score) else null
                val barColor   = if (tier != null) Color(tier.colorHex) else Slate700
                val heightFrac = if (hasData) (point.score.toFloat() / maxScore).coerceAtMost(1f) else 0.03f
                val barH       = (chartH.value * heightFrac).dp.coerceAtLeast(3.dp)
                val alpha      = when {
                    isSelected   -> 1f
                    hasSelection -> 0.25f
                    isToday      -> 1f
                    else         -> 0.45f
                }
                val dayLabel = when (selectedRange) {
                    90   -> if (index % 2 == 0) "W${index + 1}" else ""
                    30   -> if (index % 5 == 0) "${index + 1}" else ""
                    else -> point.date.takeLast(2)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = (if (selectedRange == 90) Modifier.weight(1f) else Modifier.width(22.dp))
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            if (hasData) onSelect(if (isSelected) null else index)
                        }
                ) {
                    if (selectedRange == 7) {
                        Box(modifier = Modifier.height(16.dp), contentAlignment = Alignment.BottomCenter) {
                            if (hasData) Text(
                                point.score.toString(), fontSize = 9.sp,
                                color = (if (isSelected || (isToday && !hasSelection))
                                    Color(tier!!.colorHex) else Slate500).copy(alpha = alpha),
                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                    Box(
                        modifier = Modifier
                            .then(if (selectedRange == 90) Modifier.fillMaxWidth(0.7f) else Modifier.fillMaxWidth())
                            .height(chartH),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(barH)
                                .background(barColor.copy(alpha = alpha),
                                    RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        dayLabel, fontSize = 8.sp,
                        color = when {
                            isSelected -> barColor
                            isToday    -> Slate400
                            else       -> Slate500
                        },
                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        // Tier legend
        Spacer(Modifier.height(10.dp))
        Row(
            modifier              = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            WellnessTier.entries.forEach { t ->
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(modifier = Modifier.size(7.dp).background(Color(t.colorHex), CircleShape))
                    Text(t.label, fontSize = 9.sp, color = Slate500)
                }
            }
        }
    }
}

// ── TierScaleBar ──────────────────────────────────────────────────────────────

@Composable
internal fun TierScaleBar(currentScore: Int) {
    val currentTier = WellnessTier.from(currentScore)
    val tierColor   = Color(currentTier.colorHex)
    val segments    = listOf(
        WellnessTier.OVERLOADED to 30f,
        WellnessTier.HEAVY_USE  to 20f,
        WellnessTier.DRIFTING   to 20f,
        WellnessTier.BALANCED   to 15f,
        WellnessTier.MINDFUL    to 15f
    )

    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(4.dp)).align(Alignment.Center)
            ) {
                segments.forEach { (tier, w) ->
                    Box(
                        modifier = Modifier.weight(w).fillMaxHeight()
                            .background(Color(tier.colorHex).copy(
                                alpha = if (tier == currentTier) 1f else 0.28f
                            ))
                    )
                }
            }
            val animX by animateFloatAsState(
                targetValue   = currentScore.coerceIn(0, 100) / 100f,
                animationSpec = tween(700),
                label         = "tier_dot"
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = (size.width * animX).coerceIn(6.dp.toPx(), size.width - 6.dp.toPx())
                val cy = size.height / 2f
                drawCircle(Color.White, 7.dp.toPx(), Offset(cx, cy))
                drawCircle(tierColor,  5.dp.toPx(), Offset(cx, cy))
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("0",   Modifier.weight(30f), fontSize = 8.sp, color = Slate500)
            Text("30",  Modifier.weight(20f), fontSize = 8.sp, color = Slate500)
            Text("50",  Modifier.weight(20f), fontSize = 8.sp, color = Slate500)
            Text("70",  Modifier.weight(15f), fontSize = 8.sp, color = Slate500)
            Text("85",  Modifier.weight(14f), fontSize = 8.sp, color = Slate500)
            Text("100",                       fontSize = 8.sp, color = Slate500)
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            segments.forEach { (tier, w) ->
                val isActive = tier == currentTier
                Text(
                    "${tier.emoji} ${tier.label}",
                    modifier   = Modifier.weight(w),
                    fontSize   = 8.sp,
                    color      = if (isActive) Color(tier.colorHex) else Slate500,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    maxLines   = 1
                )
            }
        }
    }
}

// ── Tier helpers ──────────────────────────────────────────────────────────────

internal data class TierNudge(val text: String, val nudgeColor: Color)

internal fun nextTierNudge(score: Int): TierNudge = when {
    score >= 85 -> TierNudge("You're at peak wellness 🌟", Color(WellnessTier.MINDFUL.colorHex))
    score >= 70 -> TierNudge("${85 - score} pts to ${WellnessTier.MINDFUL.emoji} Mindful",
        Color(WellnessTier.MINDFUL.colorHex))
    score >= 50 -> TierNudge("${70 - score} pts to ${WellnessTier.BALANCED.emoji} Balanced",
        Color(WellnessTier.BALANCED.colorHex))
    score >= 30 -> TierNudge("${50 - score} pts to ${WellnessTier.DRIFTING.emoji} Drifting",
        Color(WellnessTier.DRIFTING.colorHex))
    else        -> TierNudge("${30 - score} pts to ${WellnessTier.HEAVY_USE.emoji} Heavy Use",
        Color(WellnessTier.HEAVY_USE.colorHex))
}

internal fun tierRange(tier: WellnessTier): String = when (tier) {
    WellnessTier.MINDFUL    -> "85–100 pts"
    WellnessTier.BALANCED   -> "70–84 pts"
    WellnessTier.DRIFTING   -> "50–69 pts"
    WellnessTier.HEAVY_USE  -> "30–49 pts"
    WellnessTier.OVERLOADED -> "0–29 pts"
}

// ── Format helpers ────────────────────────────────────────────────────────────

internal fun formatTime(ms: Long): String {
    if (ms == 0L) return "–"
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}

internal fun formatMinutes(min: Float): String =
    if (min < 1f) "< 1 min" else "${min.toInt()} min"

internal fun formatTotalMinutes(totalMin: Int): String {
    if (totalMin <= 0) return "–"
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0          -> "${h}h"
        else           -> "${m}m"
    }
}

internal fun isLateNight(ms: Long): Boolean {
    if (ms == 0L) return false
    val hour = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
    return hour >= 23 || hour < 5
}

internal fun List<ScrollDay>.avgDeltaLabel(today: Int): String {
    if (isEmpty()) return "No baseline yet"
    val avg = map { it.count }.average()
    if (avg == 0.0) return "No baseline yet"
    val pct = ((today - avg) / avg * 100).toInt()
    return if (pct >= 0) "↑ $pct% vs avg" else "↓ ${-pct}% vs avg"
}

internal fun List<ScrollDay>.isAboveAvg(today: Int): Boolean {
    if (isEmpty()) return false
    return today > map { it.count }.average()
}

internal fun resolveAppName(pm: PackageManager, packageName: String): String {
    if (packageName == "other") return "📱 Other (untracked)"
    if (packageName == "unknown" || packageName.isEmpty()) return "Other"
    if (packageName == "laptop") return "💻 Laptop (Chrome)"
    return try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
