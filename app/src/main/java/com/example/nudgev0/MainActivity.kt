package com.example.nudgev0

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.nudgev0.data.ScrollDatabase
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = ScrollDatabase.getDatabase(applicationContext)
        val viewModelFactory = ScrollViewModelFactory(database.scrollDao())

        // Issue 2: Schedule the midnight rollover job
        scheduleResetWorker()

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MainScreen(factory = viewModelFactory)
                }
            }
        }
    }

    private fun scheduleResetWorker() {
        val resetRequest = PeriodicWorkRequestBuilder<ResetWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(millisUntilMidnight(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "midnight_reset",
            ExistingPeriodicWorkPolicy.KEEP,
            resetRequest
        )
    }

    private fun millisUntilMidnight(): Long {
        val now = Calendar.getInstance()
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return midnight.timeInMillis - now.timeInMillis
    }
}
