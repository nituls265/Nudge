package com.nitulshah.nudge

sealed class InterventionState {
    object Idle : InterventionState()
    object Level1 : InterventionState()
    object Level2 : InterventionState()
    object Level3 : InterventionState()
    object Cooldown : InterventionState()
}
