package com.example.nudgev0.telemetry.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import com.example.nudgev0.BuildConfig
import com.example.nudgev0.data.DayBoundary
import com.example.nudgev0.telemetry.core.InstallIdGenerator
import com.example.nudgev0.telemetry.core.TelemetryClock
import com.example.nudgev0.telemetry.core.TelemetryStorage
import com.example.nudgev0.telemetry.core.TelemetryTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

// ── Android `actual` implementations of the telemetry.core seams ──────────────
// (These are the only files that touch the platform. In a KMP build they become
//  the androidMain `actual`s.)

private val Context.telemetryDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "nudge_telemetry")

/** DataStore-backed persistence (the spec-mandated store for the install ID). */
class DataStoreTelemetryStorage(context: Context) : TelemetryStorage {
    private val ds = context.applicationContext.telemetryDataStore

    private val keyOptedIn       = booleanPreferencesKey("opted_in")
    private val keyAnswered      = booleanPreferencesKey("consent_answered")
    private val keyInstallId     = stringPreferencesKey("install_id")
    private val keyFirstOpenSent = booleanPreferencesKey("first_open_sent")
    private val keyLastActive    = stringPreferencesKey("last_active_date")
    private val keyQueue         = stringPreferencesKey("event_queue") // \n-joined JSON objects

    override suspend fun isOptedIn() = ds.data.first()[keyOptedIn] ?: false
    override suspend fun setOptedIn(value: Boolean) { ds.edit { it[keyOptedIn] = value } }

    override suspend fun hasAnsweredConsent() = ds.data.first()[keyAnswered] ?: false
    override suspend fun setConsentAnswered(value: Boolean) { ds.edit { it[keyAnswered] = value } }

    override suspend fun installId() = ds.data.first()[keyInstallId]
    override suspend fun setInstallId(id: String) { ds.edit { it[keyInstallId] = id } }
    override suspend fun clearInstallId() { ds.edit { it.remove(keyInstallId) } }

    override suspend fun firstOpenSent() = ds.data.first()[keyFirstOpenSent] ?: false
    override suspend fun setFirstOpenSent(value: Boolean) { ds.edit { it[keyFirstOpenSent] = value } }

    override suspend fun lastActiveDate() = ds.data.first()[keyLastActive]
    override suspend fun setLastActiveDate(date: String) { ds.edit { it[keyLastActive] = date } }
    override suspend fun clearLastActiveDate() { ds.edit { it.remove(keyLastActive) } }

    // Each queued item is one JSON object; jsonString() escapes any real newline,
    // so '\n' is a safe record separator.
    override suspend fun queuedEvents(): List<String> {
        val raw = ds.data.first()[keyQueue] ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }
    override suspend fun appendQueuedEvent(json: String) {
        ds.edit {
            val existing = it[keyQueue]
            it[keyQueue] = if (existing.isNullOrEmpty()) json else "$existing\n$json"
        }
    }
    override suspend fun clearQueue() { ds.edit { it.remove(keyQueue) } }
}

/**
 * Thin Supabase REST insert — no SDK. POSTs to {url}/rest/v1/events with the anon
 * key. RLS allows INSERT only; the client never reads. If the project isn't
 * configured (blank url/key) it no-ops so the app runs fine without a backend.
 */
class HttpUrlTransport(
    private val supabaseUrl: String,
    private val anonKey: String,
) : TelemetryTransport {
    override suspend fun postEvents(jsonArrayBody: String): Boolean = withContext(Dispatchers.IO) {
        // Debug-inspection: print the EXACT JSON about to leave the device. Gated to
        // debug builds via BuildConfig.DEBUG, so it is a no-op (and strippable) in
        // release. Placed before the config check so payloads are visible even when
        // no backend is configured yet.
        if (BuildConfig.DEBUG) {
            Log.d("NudgeTelemetry", "▶ events about to send: $jsonArrayBody")
        }
        if (supabaseUrl.isBlank() || anonKey.isBlank()) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            val url = URL(supabaseUrl.trimEnd('/') + "/rest/v1/events")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $anonKey")
                setRequestProperty("Prefer", "return=minimal")
            }
            conn.outputStream.use { it.write(jsonArrayBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (BuildConfig.DEBUG && code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Log.w("NudgeTelemetry", "POST failed: HTTP $code — $err")
            }
            code in 200..299
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w("NudgeTelemetry", "POST threw: ${e.javaClass.simpleName}: ${e.message}")
            false   // offline / error → keep queued, retry later
        } finally {
            conn?.disconnect()
        }
    }
}

/** System clock. Local date reuses the app's single DayBoundary definition. */
class AndroidClock : TelemetryClock {
    override fun nowUtcIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    override fun todayLocalDate(): String = DayBoundary.today()
}

/** Random UUID v4 — the ONLY identifier, with no device-derived input. */
class AndroidInstallIdGenerator : InstallIdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
