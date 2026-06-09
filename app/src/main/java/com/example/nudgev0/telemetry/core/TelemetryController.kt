package com.example.nudgev0.telemetry.core

/**
 * Platform-agnostic telemetry orchestration. ALL behaviour lives here — consent
 * gating, the two events, per-day dedupe, the offline queue, and flush/retry — so
 * it can be unit-tested with fakes and lifted into `commonMain` verbatim.
 *
 * It sends EXACTLY two event types and nothing else:
 *   • first_open — once, ever, after opt-in.
 *   • day_active — at most once per LOCAL calendar day the app is opened.
 */
class TelemetryController(
    private val storage: TelemetryStorage,
    private val transport: TelemetryTransport,
    private val clock: TelemetryClock,
    private val idGenerator: InstallIdGenerator,
    private val appVersion: String,
    private val platform: String,
) {
    suspend fun isOptedIn(): Boolean = storage.isOptedIn()
    suspend fun hasAnsweredConsent(): Boolean = storage.hasAnsweredConsent()

    /** User accepted the consent prompt (or switched the Settings toggle on). */
    suspend fun optIn() {
        if (storage.installId() == null) storage.setInstallId(idGenerator.newId())
        storage.setOptedIn(true)
        storage.setConsentAnswered(true)
        recordAppOpen()
    }

    /**
     * User declined, or turned telemetry off. Stops all sends and DELETES the
     * local install ID plus every other piece of telemetry state, so nothing
     * identifiable remains on device.
     */
    suspend fun optOut() {
        storage.setOptedIn(false)
        storage.setConsentAnswered(true)
        storage.clearQueue()
        storage.clearInstallId()
        storage.setFirstOpenSent(false)
        storage.clearLastActiveDate()
    }

    /**
     * Call on every app open. No-op unless opted in. Enqueues first_open (once)
     * and day_active (once per local day), then attempts a flush.
     *
     * The dedupe markers are persisted BEFORE the flush, so an offline flush never
     * loses or double-sends: the event stays queued for retry while the marker
     * prevents it being enqueued again.
     *
     * @return true if the queue is empty afterwards (everything sent).
     */
    suspend fun recordAppOpen(): Boolean {
        if (!storage.isOptedIn()) return false
        val installId = storage.installId() ?: return false

        if (!storage.firstOpenSent()) {
            storage.appendQueuedEvent(
                TelemetryEvent.FirstOpen(installId, appVersion, clock.nowUtcIso(), platform).toJson()
            )
            storage.setFirstOpenSent(true)
        }

        val today = clock.todayLocalDate()
        if (storage.lastActiveDate() != today) {
            storage.appendQueuedEvent(
                TelemetryEvent.DayActive(installId, appVersion, today).toJson()
            )
            storage.setLastActiveDate(today)
        }

        return flush()
    }

    /**
     * Try to send everything queued as a single batch insert. The queue is kept
     * intact on failure (offline / server error) so it retries on the next open
     * or when the platform's network-constrained flush fires.
     *
     * @return true if the queue is empty afterwards.
     */
    suspend fun flush(): Boolean {
        if (!storage.isOptedIn()) return false
        val queued = storage.queuedEvents()
        if (queued.isEmpty()) return true
        val body = queued.joinToString(prefix = "[", postfix = "]", separator = ",")
        val ok = transport.postEvents(body)
        if (ok) storage.clearQueue()
        return ok
    }
}
