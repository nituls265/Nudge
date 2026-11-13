package com.nitulshah.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNudgePolicyTest {

    @Test fun levelsFollowThresholds() {
        assertEquals(SessionNudgeLevel.NONE, SessionNudgePolicy.levelFor(0f))
        assertEquals(SessionNudgeLevel.NONE, SessionNudgePolicy.levelFor(19.9f))
        assertEquals(SessionNudgeLevel.TINT, SessionNudgePolicy.levelFor(20f))
        assertEquals(SessionNudgeLevel.PULSE, SessionNudgePolicy.levelFor(30f))
        assertEquals(SessionNudgeLevel.NOTIFY, SessionNudgePolicy.levelFor(45f))
        assertEquals(SessionNudgeLevel.BREAK, SessionNudgePolicy.levelFor(60f))
        assertEquals(SessionNudgeLevel.BREAK, SessionNudgePolicy.levelFor(240f))
    }

    @Test fun defaultModeIsPassiveBubbleOnly() {
        assertEquals(SessionNudgeMode.BUBBLE_ONLY, SessionNudgeMode.DEFAULT)
        SessionNudgeLevel.values().forEach {
            assertEquals(SessionNudgeAction.NONE, SessionNudgePolicy.actionFor(it, SessionNudgeMode.DEFAULT))
            assertEquals(SessionNudgeAction.NONE, SessionNudgePolicy.actionFor(it, SessionNudgeMode.OFF))
        }
    }

    @Test fun interruptiveActionsAreOptIn() {
        assertEquals(SessionNudgeAction.NOTIFICATION,
            SessionNudgePolicy.actionFor(SessionNudgeLevel.NOTIFY, SessionNudgeMode.NOTIFICATIONS))
        assertEquals(SessionNudgeAction.NOTIFICATION,
            SessionNudgePolicy.actionFor(SessionNudgeLevel.BREAK, SessionNudgeMode.NOTIFICATIONS))
        assertEquals(SessionNudgeAction.OVERLAY,
            SessionNudgePolicy.actionFor(SessionNudgeLevel.BREAK, SessionNudgeMode.FULL_SCREEN))
        assertEquals(SessionNudgeAction.NONE,
            SessionNudgePolicy.actionFor(SessionNudgeLevel.PULSE, SessionNudgeMode.FULL_SCREEN))
    }

    @Test fun keyIncreasesPerLevelAndEveryRepeatAfterTop() {
        val keys = listOf(20f, 30f, 45f, 60f, 74f, 75f, 90f).map {
            SessionNudgePolicy.keyFor(SessionNudgePolicy.levelFor(it), it)
        }
        assertEquals(keys, keys.sorted())
        assertEquals(keys[3], keys[4])          // 60 and 74 min: same slot
        assertTrue(keys[5] > keys[4])           // 75 min: next repeat
        assertTrue(keys[6] > keys[5])           // 90 min: next repeat
    }

    @Test fun snoozeEscalatesAndCaps() {
        assertEquals(10, SessionNudgePolicy.snoozeMinutes(0))
        assertEquals(20, SessionNudgePolicy.snoozeMinutes(1))
        assertEquals(30, SessionNudgePolicy.snoozeMinutes(2))
        assertEquals(30, SessionNudgePolicy.snoozeMinutes(9))
    }
}
