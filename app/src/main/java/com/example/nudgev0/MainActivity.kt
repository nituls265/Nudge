package com.example.nudgev0

import android.Manifest
import android.content.Context
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
import com.example.nudgev0.data.ScrollDatabase
import com.example.nudgev0.ui.theme.Nudgev0Theme
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        recordFirstLaunchDate()
        incrementChartHintCount()
        scheduleMidnightReset()
        NotificationHelper.createChannel(applicationContext)
        requestNotificationPermission()

        // Firebase anonymous sign-in — generates the Sync Code used to link the Chrome extension
        lifecycleScope.launch {
            FirebaseSyncManager.init(applicationContext)
        }

        val database = ScrollDatabase.getDatabase(applicationContext)
        val viewModelFactory = ScrollViewModelFactory(application, database.scrollDao(), database.unlockDao(), database.appScrollDao())

        setContent {
            Nudgev0Theme {
                Surface {
                    MainScreen(factory = viewModelFactory)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val currentCount = MyAccessibilityService.scrollCount.value
        if (currentCount > 0) {
            AnalyticsHelper.logSessionSnapshot(currentCount)
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
            // Backdate by 8 days so beta users skip the calibration period on fresh install
            val eightDaysAgo = System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L
            prefs.edit().putLong("FIRST_LAUNCH_DATE", eightDaysAgo).apply()
        }
    }

    private fun scheduleMidnightReset() {
        val now = Calendar.getInstance()
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val delayUntilMidnight = midnight.timeInMillis - now.timeInMillis

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