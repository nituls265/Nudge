package com.nitulshah.nudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class InterventionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {} // force a conscious choice
        })

        val sessionMinutes = intent.getIntExtra(EXTRA_SESSION_MIN, 0)

        setContent {
            InterventionOverlay(
                sessionMinutes = sessionMinutes,
                onTakeBreak = {
                    MyAccessibilityService.resetIntervention()
                    AnalyticsHelper.logInterventionResponse("break", 3)
                    finish()
                },
                onIgnore = {
                    MyAccessibilityService.startInterventionCooldown()
                    MyAccessibilityService.snoozeSessionNudge()
                    AnalyticsHelper.logInterventionResponse("ignore", 3)
                    finish()
                }
            )
        }
    }

    companion object {
        /** Set when launched by the session-length nudge; minutes on the phone this session. */
        const val EXTRA_SESSION_MIN = "session_minutes"
    }
}

@Composable
private fun InterventionOverlay(sessionMinutes: Int, onTakeBreak: () -> Unit, onIgnore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(color = Color(0xFF1A1A2E), shape = RoundedCornerShape(20.dp))
                .padding(32.dp)
        ) {
            Text(
                text = if (sessionMinutes > 0) "You've been on your phone\nfor $sessionMinutes minutes"
                       else "You've been scrolling\nfor a while",
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (sessionMinutes > 0) "Long stretches without a pause tire your eyes and your focus. A short break helps."
                       else "Constant scrolling keeps your brain in a low-grade stress state. A short break resets your focus.",
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onTakeBreak,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2DD4BF)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Take a 5-Min Break",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onIgnore
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Ignore",
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}
