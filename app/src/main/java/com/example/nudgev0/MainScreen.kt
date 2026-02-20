package com.example.nudgev0

import com.example.nudgev0.data.ScrollDay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import com.example.nudgev0.ui.theme.*
import java.util.*

@Composable
fun MainScreen(
    factory: ScrollViewModelFactory
) {
    val scrollViewModel: ScrollViewModel = viewModel(factory = factory)

    // Observables
    val currentCount by scrollViewModel.scrollCount.collectAsState()
    val timestamps by scrollViewModel.scrollTimestamps.collectAsState()
    val isBubbleVisible by scrollViewModel.isBubbleVisible.collectAsState()

    // Calculations
    val peakHourRange = remember(timestamps) { calculatePeakHourRange(timestamps) }

    val isPaused by scrollViewModel.isPaused.collectAsState()

    // --- MAIN LAYOUT ---
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground) // Readable!
            .padding(24.dp)
            .verticalScroll(rememberScrollState()), // Enables scrolling for the whole screen
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Header
        Text("ScrollTracker", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold))
        Text("Passively track your scrolling habits", color = Color.Gray, fontSize = 14.sp)

        Spacer(modifier = Modifier.height(40.dp))

        // 2. Main Counter Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(32.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$currentCount",
                        style = TextStyle(fontSize = 100.sp, fontWeight = FontWeight.Black, color = Color(0xFF059669))
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(48.dp)
                            .background(Color(0xFFD1FAE5), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚡", fontSize = 20.sp)
                    }
                }
                Text("SCROLLS TODAY", style = TextStyle(letterSpacing = 2.sp, color = Color.LightGray))

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { scrollViewModel.togglePause() },
                        // Change color: Red for Pause, Green for Resume
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPaused) Color(0xFFD1FAE5) else Color(0xFFFEF2F2)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Change Text and Text Color
                        Text(
                            text = if (isPaused) "▶ Resume" else "⏸ Pause",
                            color = if (isPaused) Color(0xFF059669) else Color(0xFFEF4444),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = { scrollViewModel.resetScrollCount() },
                        modifier = Modifier.background(Color(0xFFF3F4F6), RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color.Gray)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. Peak Hour Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("PEAK HOUR", style = TextStyle(color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = peakHourRange,
                    style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1F2937))
                )
                Text("Your highest scroll activity window.", fontSize = 12.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. Chart Section (Now outside the Peak Hour card!)
        ChartSection(viewModel = scrollViewModel)

        // 5. Bottom Toggle Button (Pushed to bottom)
        Spacer(modifier = Modifier.weight(1f))

        // 1. Get the current context (needed to check settings and open the intent)
        val context = androidx.compose.ui.platform.LocalContext.current

        TextButton(
            onClick = {
                // 2. THE GATEKEEPER CHECK
                // We use the helper function you already have in AccessibilityServiceUtils.kt
                if (isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java)) {
                    // Happy Path: Service is ON, so we toggle the bubble
                    scrollViewModel.toggleBubble()
                } else {
                    // Sad Path: Service is OFF. Redirect the user!
                    android.widget.Toast.makeText(context, "Please turn on Nudge to see the bubble", android.widget.Toast.LENGTH_LONG).show()
                    val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            },
            modifier = Modifier.padding(bottom = 16.dp, top = 24.dp)
        ) {
            Text(
                text = if (isBubbleVisible) "HIDE FLOATING BUBBLE" else "SHOW FLOATING BUBBLE",
                style = TextStyle(
                    color = if (isBubbleVisible) Color.Gray else Color(0xFF059669),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            )
        }
    }
}

// --- HELPER COMPOSABLES ---

@Composable
fun ChartSection(viewModel: ScrollViewModel) {
    val timeRange by viewModel.timeRange.collectAsState()
    val chartData by viewModel.chartData.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            // Header with Dropdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Scroll History", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Average vs. Time", color = Color.Gray, fontSize = 12.sp)
                }

                // Dropdown
                Box {
                    Button(
                        onClick = { expanded = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF3F4F6)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when (timeRange) {
                                7 -> "7 Days"
                                30 -> "30 Days"
                                90 -> "3 Months"
                                else -> "7 Days"
                            } + " ▾",
                            color = Color.Black,
                            fontSize = 12.sp
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Last 7 Days") }, onClick = { viewModel.setTimeRange(7); expanded = false })
                        DropdownMenuItem(text = { Text("Last 30 Days") }, onClick = { viewModel.setTimeRange(30); expanded = false })
                        DropdownMenuItem(text = { Text("Last 3 Months") }, onClick = { viewModel.setTimeRange(90); expanded = false })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Graph
            if (chartData.isEmpty()) {
                Box(modifier = Modifier.height(150.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No history yet. Check back tomorrow!", color = Color.Gray, fontSize = 12.sp)
                }
            } else {
                BarChart(data = chartData)
            }
        }
    }
}

@Composable
fun BarChart(data: List<ScrollDay>) {
    val maxCount = data.maxOfOrNull { it.count } ?: 1

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        data.forEach { day ->
            val barHeightRatio = day.count.toFloat() / maxCount.toFloat()

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .fillMaxHeight(barHeightRatio)
                        .background(
                            color = if (day.count > 500) Color(0xFFEF4444) else Color(0xFF10B981),
                            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        )
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Only show labels if we aren't displaying 3 months of data (too crowded)
                if (data.size <= 7) {
                    Text(
                        text = day.date.takeLast(2), // Shows "11" from "2026-02-11"
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

fun calculatePeakHourRange(timestamps: List<Long>): String {
    if (timestamps.isEmpty()) return "00:00 - 01:00"

    val hourCounts = timestamps.groupBy {
        val cal = Calendar.getInstance().apply { timeInMillis = it }
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }.mapValues { it.value.size }

    val peakStartMillis = hourCounts.maxByOrNull { it.value }?.key ?: return "00:00 - 01:00"

    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val startTime = sdf.format(Date(peakStartMillis))
    val endTime = sdf.format(Date(peakStartMillis + 3600000))

    return "$startTime - $endTime"
}