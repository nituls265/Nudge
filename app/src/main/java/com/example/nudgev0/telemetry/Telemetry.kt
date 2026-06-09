package com.example.nudgev0.telemetry

import android.content.Context
import com.example.nudgev0.telemetry.android.AndroidClock
import com.example.nudgev0.telemetry.android.AndroidInstallIdGenerator
import com.example.nudgev0.telemetry.android.DataStoreTelemetryStorage
import com.example.nudgev0.telemetry.android.HttpUrlTransport
import com.example.nudgev0.telemetry.core.TelemetryController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-facing telemetry facade — the Android wiring of the platform-agnostic
 * [TelemetryController]. A single instance per process.
 *
 * The UI observes [hasAnswered] (to decide whether to show the one-time consent
 * prompt) and [optedIn] (for the Settings toggle). All sending is gated on opt-in
 * inside the controller, so calling [onAppOpen] before consent is a safe no-op.
 */
object Telemetry {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private lateinit var storage: DataStoreTelemetryStorage
    private lateinit var controller: TelemetryController
    @Volatile private var initialized = false

    /** null = still loading from disk; false = prompt not yet answered. */
    private val _hasAnswered = MutableStateFlow<Boolean?>(null)
    val hasAnswered: StateFlow<Boolean?> = _hasAnswered.asStateFlow()

    private val _optedIn = MutableStateFlow(false)
    val optedIn: StateFlow<Boolean> = _optedIn.asStateFlow()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            storage = DataStoreTelemetryStorage(appContext)
            val version = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull() ?: "unknown"
            controller = TelemetryController(
                storage      = storage,
                transport    = HttpUrlTransport(TelemetryConfig.SUPABASE_URL, TelemetryConfig.SUPABASE_ANON_KEY),
                clock        = AndroidClock(),
                idGenerator  = AndroidInstallIdGenerator(),
                appVersion   = version,
                platform     = "android",
            )
            initialized = true
        }
        refreshState()
    }

    private fun refreshState() {
        if (!initialized) return
        scope.launch {
            _hasAnswered.value = storage.hasAnsweredConsent()
            _optedIn.value = storage.isOptedIn()
        }
    }

    /** Call on each app open. No-op unless opted in. Schedules a retry if offline. */
    fun onAppOpen() {
        if (!initialized) return
        scope.launch {
            val allSent = controller.recordAppOpen()
            if (!allSent && controller.isOptedIn()) {
                TelemetryFlushWorker.enqueue(appContext)
            }
        }
    }

    /** User accepted the consent prompt / turned the Settings toggle on. */
    fun optIn() {
        if (!initialized) return
        scope.launch {
            controller.optIn()
            refreshState()
            if (!controller.flush() && controller.isOptedIn()) {
                TelemetryFlushWorker.enqueue(appContext)
            }
        }
    }

    /** User declined / turned the toggle off. Deletes the local install ID. */
    fun optOut() {
        if (!initialized) return
        scope.launch {
            controller.optOut()
            refreshState()
        }
    }

    /** Used by the network-constrained flush worker. */
    suspend fun flushNow(): Boolean {
        if (!initialized) return false
        return controller.flush()
    }
}
