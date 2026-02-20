package com.example.nudgev0

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nudgev0.data.ScrollDao
import com.example.nudgev0.data.ScrollDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

class ScrollViewModel(private val dao: ScrollDao) : ViewModel() {

    // 1. The Service Data (Keep this)
    val scrollCount = MyAccessibilityService.scrollCount
    val scrollTimestamps = MyAccessibilityService.scrollTimestamps
    val isBubbleVisible = MyAccessibilityService.isBubbleVisible

    // 2. The Selected Time Range (Default to 7 Days)
    private val _timeRange = MutableStateFlow(7)
    val timeRange = _timeRange.asStateFlow()

    val isPaused: StateFlow<Boolean> = MyAccessibilityService.isPaused

    fun togglePause() {
        MyAccessibilityService.togglePause()
    }

    fun setTimeRange(days: Int) {
        _timeRange.value = days
    }

    // 3. The Dynamic History Pipeline
    @OptIn(ExperimentalCoroutinesApi::class)
    val chartData: StateFlow<List<ScrollDay>> = _timeRange.flatMapLatest { days ->
        // Calculate the "Start Date"
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val startDate = sdf.format(cal.time)

        // Fetch data
        dao.getHistorySince(startDate).map { rawList ->
            if (days == 90) {
                // EXPERT MOVE: If 3 months, group by Week (Chunking)
                // Otherwise, the graph looks too crowded.
                rawList.chunked(7).map { weekDays ->
                    val avg = weekDays.map { it.count }.average().toInt()
                    val label = weekDays.firstOrNull()?.date ?: ""
                    ScrollDay(label, avg)
                }
            } else {
                // For 7 or 30 days, just show the raw daily data
                rawList
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun resetScrollCount() {
        MyAccessibilityService.resetScrollCount()
    }

    fun toggleBubble() {
        MyAccessibilityService.toggleBubbleVisibility()
    }
}