package com.example.nudgev0.telemetry.core

/**
 * The EXACTLY-two telemetry events this app ever sends.
 *
 * Pure Kotlin — no platform imports — so this file (and everything else in
 * `telemetry.core`) moves to `commonMain` unchanged the day the app becomes KMP.
 *
 * Privacy: the only identifier is [installId], a locally-generated random UUID.
 * No device / advertising / hardware identifiers, no IP-derived identity, and no
 * scroll content or app-usage-tied-to-identity are ever part of these payloads.
 * Retention math needs none of that.
 */
sealed interface TelemetryEvent {
    val eventType: String
    val installId: String
    val appVersion: String

    /** Flat JSON object for one `events` row. */
    fun toJson(): String

    // NOTE: both event types emit the SAME full set of keys (null where a column
    // doesn't apply). This is required for the Supabase/PostgREST bulk insert — a
    // batch with differing keys per object is rejected (PGRST102 "All object keys
    // must match"). Keeping one uniform row shape also keeps the table tidy.

    /** Sent ONCE, ever, on the first launch after opt-in. */
    data class FirstOpen(
        override val installId: String,
        override val appVersion: String,
        val timestampUtc: String,   // ISO-8601 UTC, e.g. 2026-06-07T18:30:00Z
        val platform: String,       // supplied by the platform layer ("android" / "ios")
    ) : TelemetryEvent {
        override val eventType get() = "first_open"
        override fun toJson(): String = jsonObject(
            "event_type"    to eventType,
            "install_id"    to installId,
            "app_version"   to appVersion,
            "platform"      to platform,
            "timestamp_utc" to timestampUtc,
            "event_date"    to null,
        )
    }

    /** Sent at most ONCE per local calendar day the app is opened. */
    data class DayActive(
        override val installId: String,
        override val appVersion: String,
        val date: String,           // YYYY-MM-DD, device-LOCAL calendar date
    ) : TelemetryEvent {
        override val eventType get() = "day_active"
        override fun toJson(): String = jsonObject(
            "event_type"    to eventType,
            "install_id"    to installId,
            "app_version"   to appVersion,
            "platform"      to null,
            "timestamp_utc" to null,
            "event_date"    to date,
        )
    }
}

// ── Minimal, dependency-free JSON (so the core pulls in no serialization lib) ──

/**
 * Build a flat JSON object string. Null values are emitted as JSON `null` (NOT
 * omitted) so every event serialises the same key set — required for the
 * Supabase bulk insert (see TelemetryEvent).
 */
internal fun jsonObject(vararg pairs: Pair<String, String?>): String =
    pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
        jsonString(k) + ":" + (if (v == null) "null" else jsonString(v))
    }

/** JSON-escape and quote a string using only multiplatform-safe Kotlin. */
internal fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"'  -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else ->
                if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
