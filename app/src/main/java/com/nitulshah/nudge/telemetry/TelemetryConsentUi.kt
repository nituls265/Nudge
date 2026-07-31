package com.nitulshah.nudge.telemetry

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nitulshah.nudge.Green
import com.nitulshah.nudge.Slate400
import com.nitulshah.nudge.Slate800
import com.nitulshah.nudge.Slate900

/**
 * The one-time, opt-IN consent prompt shown on first launch. Telemetry is OFF
 * until the user taps "Help improve Nudge"; "No thanks" opts out. It is not
 * dismissable by tapping outside — we want a deliberate choice, once.
 *
 * The copy states EXACTLY what is sent, per the privacy spec.
 */
@Composable
fun TelemetryConsentDialog(
    onEnable: () -> Unit,
    onDecline: () -> Unit,
) {
    Dialog(
        onDismissRequest = { /* require an explicit choice */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Slate800, RoundedCornerShape(22.dp))
                .padding(24.dp)
        ) {
            Text("📈", fontSize = 28.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Help improve Nudge?",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = androidx.compose.ui.graphics.Color.White
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "We'd like to know if Nudge actually helps people over time. If you " +
                    "opt in, the app sends:",
                fontSize = 14.sp,
                color = Slate400,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(10.dp))
            BulletLine("an anonymous random ID (created on this device)")
            BulletLine("the dates you open the app")
            BulletLine("the app version")
            Spacer(Modifier.height(12.dp))
            Text(
                "That's it. No scrolls, no app names, no accounts, no device, " +
                    "advertising, or location identifiers — ever. It's off by default, " +
                    "and you can turn it off (and delete the ID) any time in Settings.",
                fontSize = 13.sp,
                color = Slate400,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("Help improve Nudge", fontWeight = FontWeight.Bold,
                    color = Slate900)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text("No thanks", color = Slate400)
            }
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    Row(modifier = Modifier.padding(bottom = 4.dp)) {
        Text("•  ", fontSize = 14.sp, color = Green)
        Text(text, fontSize = 14.sp, color = androidx.compose.ui.graphics.Color(0xFFE2E8F0), lineHeight = 20.sp)
    }
}
