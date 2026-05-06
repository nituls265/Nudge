package com.example.nudgev0

import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase

object AnalyticsHelper {

    private val analytics: FirebaseAnalytics by lazy { Firebase.analytics }

    fun logDailyScrollSummary(totalScrolls: Int) {
        analytics.logEvent("daily_scroll_summary") {
            param("total_scrolls", totalScrolls.toLong())
        }
    }

    fun logBubbleToggled(isVisible: Boolean) {
        analytics.logEvent("feature_bubble_toggled") {
            param("is_visible", if (isVisible) 1L else 0L)
        }
    }

    fun logPauseToggled(isPaused: Boolean) {
        analytics.logEvent("action_pause_toggled") {
            param("is_paused", if (isPaused) 1L else 0L)
        }
    }

    fun logManualReset() {
        analytics.logEvent("action_manual_reset") {}
    }
}
