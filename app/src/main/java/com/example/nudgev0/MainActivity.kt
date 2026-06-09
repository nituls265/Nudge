package com.example.nudgev0

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.nudgev0.data.DayBoundary
import com.example.nudgev0.data.NudgeRepository
import com.example.nudgev0.telemetry.Telemetry
import com.example.nudgev0.ui.theme.Nudgev0Theme
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recordFirstLaunchDate()
        incrementChartHintCount()
        scheduleMidnightReset()
        NotificationHelper.createChannel(applicationContext)
        requestNotificationPermission()

        // Retention telemetry (opt-in, off by default). init() only loads local
        // state; nothing is sent until the user opts in via the consent prompt.
        Telemetry.init(applicationContext)

        // Firebase anonymous sign-in — generates the Sync Code used to link the Chrome extension
        lifecycleScope.launch {
            FirebaseSyncManager.init(applicationContext)
        }

        val repository = NudgeRepository.get(applicationContext)
        val viewModelFactory = ScrollViewModelFactory(application, repository)

        setContent {
            Nudgev0Theme {
                Surface {
                    MainScreen(factory = viewModelFactory)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndResetIfDateChanged()
        // Records one day_active per local calendar day (deduped). No-op until opt-in.
        Telemetry.onAppOpen()
    }

    override fun onStop() {
        super.onStop()
        val currentCount = MyAccessibilityService.scrollCount.value
        if (currentCount > 0) {
            AnalyticsHelper.logSessionSnapshot(currentCount)
        }
    }

    // If the user opens the app after midnight before their first scroll,
    // the accessibility service hasn't had a chance to detect the date change yet.
    // Reset live counters here so the chart shows 0 for today, not yesterday's total.
    private fun checkAndResetIfDateChanged() {
        val prefs = getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
        val lastDate = prefs.getString("LAST_SCROLL_DATE", "") ?: ""
        val today = DayBoundary.today()
        if (lastDate.isNotEmpty() && lastDate != today) {
            MyAccessibilityService.resetScrollCount()
            MyAccessibilityService.resetUnlockCount()
        }
    }

    private fun incrementChartHintCount() {
        val prefs = getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("CHART_HINT_COUNT", 0)
        if (count < 3) prefs.edit().putInt("CHART_HINT_COUNT", count + 1).apply()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    private fun recordFirstLaunchDate() {
        val prefs = getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
        if (!prefs.contains("FIRST_LAUNCH_DATE")) {
            // The old code hard-coded an 8-day backdate for ALL builds, which
            // silently disabled the 7-day calibration period for every real
            // user. Now only DEBUG builds skip calibration (developer convenience
            // — no week-long wait on each fresh install); release builds record
            // the real first-launch date so genuine new users calibrate properly.
            val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val firstLaunch = if (isDebuggable) {
                System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000   // skip calibration
            } else {
                System.currentTimeMillis()                              // real baseline
            }
            prefs.edit().putLong("FIRST_LAUNCH_DATE", firstLaunch).apply()
        }
    }

    private fun scheduleMidnightReset() {
        val now = System.currentTimeMillis()
        val delayUntilMidnight = DayBoundary.nextMidnightMillis(now) - now

        val resetWork = PeriodicWorkRequestBuilder<ResetWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilMidnight, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "midnight_reset",
            ExistingPeriodicWorkPolicy.KEEP,
            resetWork
        )
    }
}