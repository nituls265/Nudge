package com.example.nudgev0

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.nudgev0.data.ScrollDatabase
import com.example.nudgev0.data.ScrollDay
import java.text.SimpleDateFormat
import java.util.*

// NO LONGER NEED TO IMPORT Room HERE

class ResetWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val database = ScrollDatabase.getDatabase(applicationContext)
        val dao = database.scrollDao()

        // 1. Capture the scroll count from the Service before resetting
        val countToSave = MyAccessibilityService.scrollCount.value

        // 2. Get the date for "Yesterday" (since it's now midnight)
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val yesterdayDate = sdf.format(calendar.time)

        return try {
            // 3. Save to Database
            dao.insertOrUpdate(ScrollDay(yesterdayDate, countToSave))

            // 4. Reset the live counter
            MyAccessibilityService.resetScrollCount()

            Result.success()
        } catch (e: Exception) {
            Result.retry() // Try again if the database was busy
        }
    }
}
