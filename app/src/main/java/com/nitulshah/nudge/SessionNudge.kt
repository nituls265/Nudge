package com.nitulshah.nudge

import android.content.Context

/**
 * Break reminders driven by how long the phone has been in continuous use.
 *
 * Separate from the scroll-velocity interventions: those fire on scroll *speed*,
 * so a long low-scroll session (video, reading, chat) never triggered anything.
 * This tracks session *length* instead and escalates gradually.
 *
 * Passive by default: the bubble only. Notifications and the full-screen
 * overlay are opt-in via [SessionNudgeMode].
 */
enum class SessionNudgeMode(val label: String, val description: String) {
    OFF("Off", "No session-length reminders."),
    BUBBLE_ONLY("Bubble only", "The bubble tints, then pulses as your session gets long. Nothing else."),
    NOTIFICATIONS("Bubble + notification", "Adds a gentle notification after 45 minutes."),
    FULL_SCREEN("Bubble + notification + break screen", "Adds a full-screen break prompt after 60 minutes.");

    companion object {
        val DEFAULT = BUBBLE_ONLY
    }
}

/** Escalation steps. [minutes] is active (non-call) time in the current session. */
enum class SessionNudgeLevel(val minutes: Int) {
    NONE(0),
    TINT(20),     // bubble text tints softly, no motion
    PULSE(30),    // bubble pulses amber
    NOTIFY(45),   // notification (opt-in)
    BREAK(60)     // full-screen break prompt (opt-in), repeats every REPEAT_MIN
}

enum class SessionNudgeAction { NONE, NOTIFICATION, OVERLAY }

object SessionNudgePolicy {
    /** A screen-off shorter than this does not end the session. */
    const val GRACE_MS = 2 * 60_000L
    /** Once past the top level, re-nudge this often. */
    const val REPEAT_MIN = 15

    fun levelFor(activeMin: Float): SessionNudgeLevel =
        SessionNudgeLevel.values().last { activeMin >= it.minutes }

    /** Which interruptive action (if any) this level calls for under [mode]. */
    fun actionFor(level: SessionNudgeLevel, mode: SessionNudgeMode): SessionNudgeAction = when (mode) {
        SessionNudgeMode.OFF, SessionNudgeMode.BUBBLE_ONLY -> SessionNudgeAction.NONE
        SessionNudgeMode.NOTIFICATIONS -> when (level) {
            SessionNudgeLevel.NOTIFY, SessionNudgeLevel.BREAK -> SessionNudgeAction.NOTIFICATION
            else -> SessionNudgeAction.NONE
        }
        SessionNudgeMode.FULL_SCREEN -> when (level) {
            SessionNudgeLevel.NOTIFY -> SessionNudgeAction.NOTIFICATION
            SessionNudgeLevel.BREAK -> SessionNudgeAction.OVERLAY
            else -> SessionNudgeAction.NONE
        }
    }

    /**
     * Monotonic key that increases each time an action is due: once per level,
     * then once per [REPEAT_MIN] after the top level. The caller acts only when
     * the key exceeds the last one it acted on.
     */
    fun keyFor(level: SessionNudgeLevel, activeMin: Float): Int {
        val top = SessionNudgeLevel.BREAK
        val repeat = if (level == top) ((activeMin - top.minutes) / REPEAT_MIN).toInt().coerceAtLeast(0) else 0
        return level.ordinal * 100 + repeat
    }

    /** Snooze after the user taps Ignore: 10, then 20, then 30 min (capped). */
    fun snoozeMinutes(previousSnoozes: Int): Int = (10 * (previousSnoozes + 1)).coerceAtMost(30)
}

object SessionNudgePrefs {
    private const val KEY = "SESSION_NUDGE_MODE"

    fun mode(context: Context): SessionNudgeMode {
        val name = context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE).getString(KEY, null)
        return SessionNudgeMode.values().firstOrNull { it.name == name } ?: SessionNudgeMode.DEFAULT
    }

    fun setMode(context: Context, mode: SessionNudgeMode) {
        context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}
