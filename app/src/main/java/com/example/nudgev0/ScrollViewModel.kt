package com.example.nudgev0

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nudgev0.data.ScrollDao
import com.example.nudgev0.data.ScrollDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ScrollViewModel(private val dao: ScrollDao) : ViewModel() {

    val scrollCount = MyAccessibilityService.scrollCount
    val scrollTimestamps = MyAccessibilityService.scrollTimestamps
    val isBubbleVisible = MyAccessibilityService.isBubbleVisible
    val isPaused: StateFlow<Boolean> = MyAccessibilityService.isPaused

    private val _timeRange = MutableStateFlow(7)
    val timeRange = _timeRange.asStateFlow()

    fun setTimeRange(days: Int) {
        _timeRange.value = days
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val chartData: StateFlow<List<ScrollDay>> = _timeRange.flatMapLatest { days ->
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -days)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val startDate = sdf.format(cal.time)

        dao.getHistorySince(startDate).map { rawList ->
            if (days == 90) {
                rawList.chunked(7).map { weekDays ->
                    val avg = weekDays.map { it.count }.average().toInt()
                    val label = weekDays.firstOrNull()?.date ?: ""
                    ScrollDay(label, avg)
                }
            } else {
                rawList
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun togglePause() {
        MyAccessibilityService.togglePause()
        AnalyticsHelper.logPauseToggled(MyAccessibilityService.isPaused.value)
    }

    fun toggleBubble() {
        MyAccessibilityService.toggleBubbleVisibility()
        AnalyticsHelper.logBubbleToggled(MyAccessibilityService.isBubbleVisible.value)
    }

    // Issue 4: Save today's count to Room before wiping the live counter
    fun resetAndSave() {
        viewModelScope.launch {
            val count = MyAccessibilityService.scrollCount.value
            if (count > 0) {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                dao.insertOrUpdate(ScrollDay(today, count))
            }
            MyAccessibilityService.resetScrollCount()
            AnalyticsHelper.logManualReset()
        }
    }
}
