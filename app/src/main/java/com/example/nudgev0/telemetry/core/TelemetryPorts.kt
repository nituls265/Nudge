package com.example.nudgev0.telemetry.core

/**
 * The platform "seams" the telemetry core depends on. Pure interfaces — in a KMP
 * build these become `expect` declarations (or stay interfaces wired by DI, which
 * is the more idiomatic modern KMP approach). The Android module provides the
 * `actual` implementations (DataStore, HttpURLConnection, UUID, system clock).
 */

/** Persistent local state for telemetry. */
interface TelemetryStorage {
    suspend fun isOptedIn(): Boolean
    suspend fun setOptedIn(value: Boolean)

    /** Whether the user has answered the one-time consent prompt at all. */
    suspend fun hasAnsweredConsent(): Boolean
    suspend fun setConsentAnswered(value: Boolean)

    suspend fun installId(): String?
    suspend fun setInstallId(id: String)
    suspend fun clearInstallId()

    suspend fun firstOpenSent(): Boolean
    suspend fun setFirstOpenSent(value: Boolean)

    /** Last YYYY-MM-DD a day_active was recorded — the per-day dedupe key. */
    suspend fun lastActiveDate(): String?
    suspend fun setLastActiveDate(date: String)
    suspend fun clearLastActiveDate()

    /** Offline queue of not-yet-sent event JSON objects. */
    suspend fun queuedEvents(): List<String>
    suspend fun appendQueuedEvent(json: String)
    suspend fun clearQueue()

    /** Offline queue for product-analytics events (separate table/endpoint). */
    suspend fun queuedProductEvents(): List<String>
    suspend fun appendQueuedProductEvent(json: String)
    suspend fun clearProductEventQueue()
}

/** Thin HTTP POST. The implementation holds the endpoint + anon key. */
interface TelemetryTransport {
    /** POST a JSON array body of event objects. Returns true on a 2xx insert. */
    suspend fun postEvents(jsonArrayBody: String): Boolean

    /** POST a JSON array body of product-analytics event objects. */
    suspend fun postProductEvents(jsonArrayBody: String): Boolean
}

/** Time, abstracted so the core stays platform-agnostic and testable. */
interface TelemetryClock {
    /** Current instant as ISO-8601 UTC. */
    fun nowUtcIso(): String
    /** Today's date in the device's LOCAL timezone, YYYY-MM-DD. */
    fun todayLocalDate(): String
}

/** Generates the anonymous install ID. */
interface InstallIdGenerator {
    /** A fresh random UUID. No device-derived input. */
    fun newId(): String
}
