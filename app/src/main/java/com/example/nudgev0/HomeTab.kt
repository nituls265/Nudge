package com.example.nudgev0

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// ── HomeTab ───────────────────────────────────────────────────────────────────

@Composable
fun HomeTab(vm: ScrollViewModel, onSettingsClick: () -> Unit) {
    val context = LocalContext.current

    // ── State collection — only what HomeTab actually needs ───────────────────
    // Each collectAsState() subscribes to exactly one StateFlow.
    // SharingStarted.WhileSubscribed(5000) in the VM means flows stop after
    // 5 s with no subscribers, so switching to the History tab incurs no
    // battery cost for data this screen no longer needs.
    val wellnessScore    by vm.wellnessScore.collectAsState()
    val scoreDelta       by vm.scoreDelta.collectAsState()
    val scoreTrendLabel  by vm.scoreTrendLabel.collectAsState()
    val totalScrollCount by vm.totalScrollCount.collectAsState()
    val scrollCount      by vm.scrollCount.collectAsState()
    val laptopCount      by vm.laptopCount.collectAsState()
    val unlockCount      by vm.unlockCount.collectAsState()
    val screenTimeMin    by vm.todayScreenTimeMin.collectAsState()

    // Calibration state — read once per composition; SharedPrefs is fast and
    // this value only changes at midnight, so remember is correct here.
    val calibrationDaysRemaining = remember {
        val prefs = context.getSharedPreferences("NudgePrefs", android.content.Context.MODE_PRIVATE)
        val first = prefs.getLong("FIRST_LAUNCH_DATE", System.currentTimeMillis())
        maxOf(0, 7 - TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - first).toInt())
    }
    val isCalibrating = calibrationDaysRemaining > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        // Header
        ScreenHeader(onSettingsClick = onSettingsClick)

        Spacer(Modifier.height(32.dp))

        // ── Hero: score ring or calibration ring ──────────────────────────────
        if (isCalibrating) {
            CalibrationRingHero(daysRemaining = calibrationDaysRemaining)
        } else {
            ScoreRingHero(
                score      = wellnessScore,
                delta      = scoreDelta,
                trendLabel = scoreTrendLabel
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── Compact metric strip ──────────────────────────────────────────────
        CompactMetricStrip(
            totalScrolls  = totalScrollCount,
            phoneScrolls  = scrollCount,
            laptopScrolls = laptopCount,
            unlocks       = unlockCount,
            screenTimeMin = screenTimeMin
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = Slate800)
        Spacer(Modifier.height(20.dp))

        // ── Score drivers (always visible — never hidden behind an expand) ────
        if (!isCalibrating) {
            SubMetricBarsSection(score = wellnessScore)
        } else {
            // During calibration the sub-metrics have no meaning yet, so show
            // the calibration progress card here instead.
            CalibrationCard(daysRemaining = calibrationDaysRemaining)
        }

        Spacer(Modifier.height(40.dp))
    }
}

// ── Score ring hero ───────────────────────────────────────────────────────────

@Composable
private fun ScoreRingHero(
    score: WellnessScore,
    delta: Int?,
    trendLabel: String
) {
    val tierColor = Color(score.tier.colorHex)

    // Animate both the arc sweep and the numeric label so they move together
    val animatedScore by animateFloatAsState(
        targetValue   = score.total.toFloat(),
        animationSpec = tween(durationMillis = 900),
        label         = "ring_score"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {

        // ── Ring ──────────────────────────────────────────────────────────────
        Box(
            modifier         = Modifier.size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val sw  = 11.dp.toPx()
                val r   = (size.minDimension - sw) / 2f
                val tl  = Offset((size.width  - r * 2) / 2f, (size.height - r * 2) / 2f)
                val arc = Size(r * 2, r * 2)

                // Track (empty arc)
                drawArc(
                    color      = Slate900,
                    startAngle = 135f, sweepAngle = 270f,
                    useCenter  = false,
                    style      = Stroke(sw, cap = StrokeCap.Round),
                    topLeft    = tl, size = arc
                )
                // Filled arc — colour comes from tier
                drawArc(
                    color      = tierColor,
                    startAngle = 135f,
                    sweepAngle = 270f * (animatedScore / 100f).coerceIn(0f, 1f),
                    useCenter  = false,
                    style      = Stroke(sw, cap = StrokeCap.Round),
                    topLeft    = tl, size = arc
                )
            }

            // Emoji + numeric score inside the ring
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(score.tier.emoji, fontSize = 24.sp)
                Text(
                    animatedScore.toInt().toString(),
                    style         = MaterialTheme.typography.headlineLarge,
                    fontWeight    = FontWeight.ExtraBold,
                    color         = tierColor,
                    letterSpacing = (-1).sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Tier label ────────────────────────────────────────────────────────
        Text(
            score.tier.label,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color      = tierColor
        )

        Spacer(Modifier.height(6.dp))

        // ── Delta vs yesterday ────────────────────────────────────────────────
        // Only shown once we have at least one full prior day in the DB
        if (delta != null) {
            val isPositive = delta >= 0
            val deltaText  = if (isPositive) "↑ +$delta vs yesterday" else "↓ $delta vs yesterday"
            Text(
                deltaText,
                style      = MaterialTheme.typography.labelMedium,
                color      = if (isPositive) Green else Red,
                fontWeight = FontWeight.SemiBold
            )
        }

        // ── Trend label — "Best in X days" etc. ──────────────────────────────
        if (trendLabel.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                trendLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Slate400
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Next-tier nudge ───────────────────────────────────────────────────
        val nudge = nextTierNudge(score.total)
        Text(
            nudge.text,
            style      = MaterialTheme.typography.labelMedium,
            color      = nudge.nudgeColor,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center
        )
    }
}

// ── Calibration ring (shown instead of score ring during the 7-day window) ───

@Composable
private fun CalibrationRingHero(daysRemaining: Int) {
    val currentDay = (8 - daysRemaining).coerceIn(1, 7)
    val progress   = currentDay / 7f

    val animatedProgress by animateFloatAsState(
        targetValue   = progress,
        animationSpec = tween(900),
        label         = "calibration_progress"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier         = Modifier.size(140.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val sw  = 11.dp.toPx()
                val r   = (size.minDimension - sw) / 2f
                val tl  = Offset((size.width  - r * 2) / 2f, (size.height - r * 2) / 2f)
                val arc = Size(r * 2, r * 2)
                drawArc(
                    color = Slate700, startAngle = 135f, sweepAngle = 270f,
                    useCenter = false, style = Stroke(sw, cap = StrokeCap.Round),
                    topLeft = tl, size = arc
                )
                drawArc(
                    color = Blue,
                    startAngle = 135f,
                    sweepAngle = 270f * animatedProgress.coerceIn(0f, 1f),
                    useCenter = false, style = Stroke(sw, cap = StrokeCap.Round),
                    topLeft = tl, size = arc
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📊", fontSize = 24.sp)
                Text(
                    "$currentDay/7",
                    style         = MaterialTheme.typography.headlineMedium,
                    fontWeight    = FontWeight.ExtraBold,
                    color         = Blue,
                    letterSpacing = (-1).sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text("Calibrating", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold, color = Blue)

        Spacer(Modifier.height(6.dp))

        Text("Learning your habits…", style = MaterialTheme.typography.labelMedium, color = Slate400)

        Spacer(Modifier.height(4.dp))

        Text(
            "$daysRemaining day${if (daysRemaining != 1) "s" else ""} until your score unlocks",
            style = MaterialTheme.typography.labelSmall,
            color = Slate500
        )
    }
}

// ── Compact metric strip ──────────────────────────────────────────────────────

@Composable
private fun CompactMetricStrip(
    totalScrolls: Int,
    phoneScrolls: Int,
    laptopScrolls: Int,
    unlocks: Int,
    screenTimeMin: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(18.dp),
        colors   = CardDefaults.cardColors(containerColor = Slate800)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // Scrolls — show phone + laptop split when laptop is synced
            val scrollValue = if (laptopScrolls > 0)
                "📱$phoneScrolls+💻$laptopScrolls"
            else
                totalScrolls.toString()
            MetricPill(label = "Scrolls",     value = scrollValue,                       color = MetricScrolls)

            // Thin divider
            Box(modifier = Modifier.width(1.dp).height(30.dp).background(Slate700))

            MetricPill(label = "Unlocks",     value = unlocks.toString(),                color = MetricUnlocks)

            Box(modifier = Modifier.width(1.dp).height(30.dp).background(Slate700))

            MetricPill(label = "Screen Time", value = formatTotalMinutes(screenTimeMin), color = MetricTime)
        }
    }
}

// MetricPill v2: value in neutral primary text (calm data, not status).
// Category keyed by a small muted dot beside the label — much quieter than
// a fully-coloured value, so metrics never compete with tier/score hues.
@Composable
private fun MetricPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color      = MaterialTheme.colorScheme.onBackground,
            maxLines   = 1
        )
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape))
            Text(
                label,
                style    = MaterialTheme.typography.labelSmall,
                color    = Slate400,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ── Sub-metric bars (always visible — no expand/collapse) ─────────────────────

@Composable
private fun SubMetricBarsSection(score: WellnessScore) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "SCORE DRIVERS",
            style         = MaterialTheme.typography.labelSmall,
            color         = Slate400,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )

        Spacer(Modifier.height(14.dp))

        // Each bar is coloured by its own % of max, on the same tier scale as
        // the overall score (see ScoreComponent), so good vs. weak drivers read
        // at a glance.
        ScoreComponent("Scroll Volume",    score.scrollVolume,     30)
        ScoreComponent("Session Length",   score.sessionBehaviour, 20)
        ScoreComponent("Unlock Frequency", score.unlockFrequency,  15)
        ScoreComponent("Time Hygiene",     score.timeHygiene,      20)
        ScoreComponent("App Quality",      score.appQuality,       15)

        // Flagged apps callout — only visible when relevant
        if (score.flaggedApps.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlaggedAppCallout(apps = score.flaggedApps)
        }
    }
}

// ── Flagged app callout ───────────────────────────────────────────────────────

@Composable
private fun FlaggedAppCallout(apps: List<String>) {
    val orange = Color(0xFFF97316)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate900, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("⚠️", fontSize = 12.sp)
        Text(
            "App quality reduced by",
            style    = MaterialTheme.typography.labelSmall,
            color    = Slate400,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier              = Modifier.horizontalScroll(rememberScrollState())
        ) {
            apps.forEach { app ->
                Text(
                    app,
                    style      = MaterialTheme.typography.labelSmall,
                    color      = orange,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 11.sp,
                    modifier   = Modifier
                        .background(orange.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}
