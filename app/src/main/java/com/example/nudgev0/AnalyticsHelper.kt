package com.example.nudgev0

import com.example.nudgev0.telemetry.Telemetry
import com.example.nudgev0.telemetry.core.jsonString

/**
 * Product-analytics events (feature usage, intervention funnel) — sent via the
 * opt-in Supabase telemetry pipe ([Telemetry]), never a proprietary SDK.
 */
object AnalyticsHelper {

    // 1. Track the Daily Total (Fired at midnight)
    fun logDailySummary(totalScrolls: Int) {
        if (!BuildConfig.ENABLE_TELEMETRY) return
        Telemetry.logProductEvent("daily_scroll_summary", "{\"total_scrolls\":$totalScrolls}")
    }

    // 2. Track Feature Usage: Bubble Toggled
    fun logBubbleToggled(isVisible: Boolean) {
        if (!BuildConfig.ENABLE_TELEMETRY) return
        Telemetry.logProductEvent("feature_bubble_toggled", "{\"is_visible\":$isVisible}")
    }

    // 3. Track Friction: User Paused the App
    fun logPauseToggled(isPaused: Boolean) {
        if (!BuildConfig.ENABLE_TELEMETRY) return
        Telemetry.logProductEvent("action_pause_toggled", "{\"is_paused\":$isPaused}")
    }

    // 4. Track Frustration: User manually reset today's data
    fun logManualReset() {
        if (!BuildConfig.ENABLE_TELEMETRY) return
        Telemetry.logProductEvent("action_manual_reset")
    }

    // 5. Snapshot when user closes the app — ensures data isn't lost before midnight
    fun logSessionSnapshot(totalScrolls: Int) {
        if (!BuildConfig.ENABLE_TELEMETRY) return
        Telemetry.logProductEvent("scroll_session_snapshot", "{\"total_scrolls\":$totalScrolls}")
    }

    // 6. Intervention funnel: which level triggered
    fun logInterventionTriggered(level: Int) {
        if (!BuildConfig.ENABLE_TELEMETRY) return
        Telemetry.logProductEvent("intervention_triggered", "{\"level\":$level}")
    }

    // 7. Intervention funnel: what the user chose (break / ignore)
    fun logInterventionResponse(response: String, level: Int) {
        if (!BuildConfig.ENABLE_TELEMETRY) return
        Telemetry.logProductEvent(
            "intervention_response",
            "{\"response\":${jsonString(response)},\"level\":$level}"
        )
    }
}
