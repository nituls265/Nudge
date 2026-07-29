package com.example.nudgev0

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nudgev0.telemetry.Telemetry
import java.util.concurrent.TimeUnit

// ── SettingsSheet ─────────────────────────────────────────────────────────────
// Presented as a ModalBottomSheet so it slides up over whatever tab is active.
// All destructive or permission-gating actions live here, not in the data views.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: ScrollViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current

    val isBubbleVisible by vm.isBubbleVisible.collectAsState()
    val isPaused        by vm.isPaused.collectAsState()
    val syncCode        by vm.syncCode.collectAsState()

    // Read calibration state once — same SharedPrefs trick as HomeTab
    val installDaysRemaining = remember {
        val prefs = context.getSharedPreferences("NudgePrefs", android.content.Context.MODE_PRIVATE)
        val first = prefs.getLong("FIRST_LAUNCH_DATE", System.currentTimeMillis())
        maxOf(0, 7 - TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - first).toInt())
    }
    // Kept in sync with HomeTab's data-driven re-trigger — see
    // ScrollViewModel.scrollBaselineDaysRemaining for why this isn't purely
    // install-date-based. Both are full 7-day windows, kept as a separate
    // pair since the underlying reason differs even though the total matches.
    val scrollBaselineDaysRemaining by vm.scrollBaselineDaysRemaining.collectAsState()
    val isCalibrating = installDaysRemaining > 0 || scrollBaselineDaysRemaining > 0
    val calibrationDaysRemaining = if (installDaysRemaining > 0) installDaysRemaining else scrollBaselineDaysRemaining
    val calibrationTotalDays     = 7

    // Both overlay permission AND accessibility service must be granted for the
    // bubble to function. Check this here rather than in the VM so it reflects
    // whatever the user last changed in system settings.
    val permissionsGranted = Settings.canDrawOverlays(context) &&
        isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = Slate800,
        tonalElevation   = 0.dp,
        // Allow drag-to-dismiss so the sheet never traps the user
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                "Settings",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color      = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(20.dp))

            // Calibration progress card — only shown during the 7-day window
            if (isCalibrating) {
                CalibrationCard(daysRemaining = calibrationDaysRemaining, totalDays = calibrationTotalDays)
                Spacer(Modifier.height(20.dp))
            }

            // ── Bubble toggle ─────────────────────────────────────────────────
            // Guides the user through the permission flow if either permission
            // is missing, rather than silently doing nothing.
            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        when {
                            !Settings.canDrawOverlays(context) ->
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            !isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java) ->
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            else -> vm.toggleBubble()
                        }
                    },
                    enabled            = !isCalibrating || !permissionsGranted,
                    modifier           = Modifier.fillMaxWidth(),
                    shape              = RoundedCornerShape(16.dp),
                    contentPadding     = PaddingValues(vertical = 16.dp)
                ) {
                    Text(if (isBubbleVisible) "Hide Bubble" else "Show Bubble")
                }
                if (isCalibrating && permissionsGranted) {
                    LockBadge(Modifier.align(Alignment.TopEnd))
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Pause / Resume ────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick        = { vm.togglePause() },
                    enabled        = !isCalibrating,
                    modifier       = Modifier.fillMaxWidth(),
                    shape          = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text(
                        if (isPaused) "Resume Tracking" else "Pause Tracking",
                        color = if (isCalibrating)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else
                            MaterialTheme.colorScheme.onBackground
                    )
                }
                if (isCalibrating) LockBadge(Modifier.align(Alignment.TopEnd))
            }

            Spacer(Modifier.height(20.dp))

            // ── Laptop sync ───────────────────────────────────────────────────
            LaptopSyncCard(syncCode = syncCode, context = context)

            Spacer(Modifier.height(20.dp))

            // ── Anonymous analytics (opt-in retention telemetry) ──────────────
            val telemetryOn by Telemetry.optedIn.collectAsState()
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Anonymous analytics",
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Sends only an anonymous ID + the dates you open the app, so we " +
                            "can see if Nudge helps. No personal data. Turning this off " +
                            "deletes the ID.",
                        style      = MaterialTheme.typography.bodySmall,
                        color      = Slate500,
                        lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked         = telemetryOn,
                    onCheckedChange = { on -> if (on) Telemetry.optIn() else Telemetry.optOut() }
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Destructive reset — disabled during calibration ───────────────
            TextButton(
                onClick  = {
                    vm.resetScrollCount()
                    onDismiss()
                },
                enabled  = !isCalibrating,
                colors   = ButtonDefaults.textButtonColors(
                    contentColor         = MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.38f)
                )
            ) {
                Text("Reset Today's Data", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(20.dp))

            // ── App version — quiet footer, below all actionable settings so it
            // never competes with them, but still easy to find when needed
            // (bug reports, support, "am I on the latest build" checks).
            // versionCode is internal build-bookkeeping, not user-facing info —
            // only show it in debug builds; release users just see the plain
            // marketing version. ─────────────────────────────────────────────
            val versionLabel = if (BuildConfig.DEBUG)
                "Nudge v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            else
                "Nudge v${BuildConfig.VERSION_NAME}"
            Text(
                versionLabel,
                modifier   = Modifier.fillMaxWidth(),
                textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
                style      = MaterialTheme.typography.labelSmall,
                color      = Slate500
            )
        }
    }
}
