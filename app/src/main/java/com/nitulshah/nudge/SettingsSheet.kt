package com.nitulshah.nudge

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import com.nitulshah.nudge.telemetry.Telemetry

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
                    modifier           = Modifier.fillMaxWidth(),
                    shape              = RoundedCornerShape(16.dp),
                    contentPadding     = PaddingValues(vertical = 16.dp)
                ) {
                    Text(if (isBubbleVisible) "Hide Bubble" else "Show Bubble")
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Pause / Resume ────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick        = { vm.togglePause() },
                    modifier       = Modifier.fillMaxWidth(),
                    shape          = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text(
                        if (isPaused) "Resume Tracking" else "Pause Tracking",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Break reminders (session length) ─────────────────────────────
            // Passive by default: bubble only. Notification / full-screen are opt-in.
            var nudgeMode by remember { mutableStateOf(SessionNudgePrefs.mode(context)) }
            Text(
                "Break reminders",
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Based on how long you've been on your phone in one go. Needs the bubble on.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate500
            )
            SessionNudgeMode.values().forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            nudgeMode = m
                            SessionNudgePrefs.setMode(context, m)
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = nudgeMode == m, onClick = {
                        nudgeMode = m
                        SessionNudgePrefs.setMode(context, m)
                    })
                    Column {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground)
                        Text(m.description, style = MaterialTheme.typography.bodySmall, color = Slate500)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Laptop sync ───────────────────────────────────────────────────
            LaptopSyncCard(syncCode = syncCode, context = context)

            Spacer(Modifier.height(20.dp))

            // ── Anonymous analytics (opt-in retention telemetry) ──────────────
            // Builds with telemetry compiled out (the foss / F-Droid / GitHub APK)
            // never initialize Telemetry, so this switch would do nothing there.
            // Hide it rather than show a control that silently has no effect.
            if (BuildConfig.ENABLE_TELEMETRY) {
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
                            color      = Slate500
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked         = telemetryOn,
                        onCheckedChange = { on -> if (on) Telemetry.optIn() else Telemetry.optOut() }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Destructive reset ─────────────────────────────────────────────
            TextButton(
                onClick  = {
                    vm.resetScrollCount()
                    onDismiss()
                },
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
