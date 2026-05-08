package com.example.nudgev0

import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsHelper {

    // Bulletproof initialization bypassing KTX entirely
    private val firebaseAnalytics: FirebaseAnalytics by lazy {
        FirebaseAnalytics.getInstance(FirebaseApp.getInstance().applicationContext)
    }

    // 1. Track the Daily Total (Fired at midnight)
    fun logDailySummary(totalScrolls: Int) {
        val bundle = Bundle().apply {
            putInt("total_scrolls", totalScrolls)
        }
        firebaseAnalytics.logEvent("daily_scroll_summary", bundle)
    }

    // 2. Track Feature Usage: Bubble Toggled
    fun logBubbleToggled(isVisible: Boolean) {
        val bundle = Bundle().apply {
            putBoolean("is_visible", isVisible)
        }
        firebaseAnalytics.logEvent("feature_bubble_toggled", bundle)
    }

    // 3. Track Friction: User Paused the App
    fun logPauseToggled(isPaused: Boolean) {
        val bundle = Bundle().apply {
            putBoolean("is_paused", isPaused)
        }
        firebaseAnalytics.logEvent("action_pause_toggled", bundle)
    }

    // 4. Track Frustration: User manually reset today's data
    fun logManualReset() {
        firebaseAnalytics.logEvent("action_manual_reset", null)
    }

    // 5. Snapshot when user closes the app — ensures data isn't lost before midnight
    fun logSessionSnapshot(totalScrolls: Int) {
        val bundle = Bundle().apply {
            putInt("total_scrolls", totalScrolls)
        }
        firebaseAnalytics.logEvent("scroll_session_snapshot", bundle)
    }

    // 6. Intervention funnel: which level triggered
    fun logInterventionTriggered(level: Int) {
        val bundle = Bundle().apply { putInt("level", level) }
        firebaseAnalytics.logEvent("intervention_triggered", bundle)
    }

    // 7. Intervention funnel: what the user chose (break / ignore)
    fun logInterventionResponse(response: String, level: Int) {
        val bundle = Bundle().apply {
            putString("response", response)
            putInt("level", level)
        }
        firebaseAnalytics.logEvent("intervention_response", bundle)
    }
}