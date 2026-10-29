package com.nitulshah.nudge

import android.content.Context
import com.nitulshah.nudge.telemetry.TelemetryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Phone ⇄ laptop scroll-count sync via a plain Supabase PostgREST table
 * (public.sync_state — see supabase/migrations/20260721120000_product_events_and_sync.sql).
 * No SDK: raw HttpURLConnection, same style as telemetry/android/AndroidPorts.kt.
 *
 * The Sync Code is a random 12-char ID generated on-device — no server round
 * trip needed, unlike the old Firebase-anonymous-auth-derived UID — pasted
 * into the Chrome extension. Same shared-secret trust model as before: the
 * extension's REST calls were always unauthenticated, so the code itself
 * (not Firebase Auth) was already the only thing gating access to a row.
 *
 * Firebase's real-time push (ValueEventListener) has no equivalent without a
 * websocket client here, so laptop counts are polled every 5s instead —
 * matching the refresh cadence the Chrome extension's own popup already uses.
 */
object SyncManager {

    private const val POLL_INTERVAL_MS = 5_000L
    private const val PREFS_KEY_SYNC_ID = "NUDGE_SYNC_ID"
    private const val PREFS_KEY_SYNC_ENABLED = "NUDGE_SYNC_ENABLED"
    private const val NO_CODE = "—"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncCodeFlow = MutableStateFlow(NO_CODE)
    val syncCodeFlow: StateFlow<String> = _syncCodeFlow

    private fun prefs(context: Context) =
        context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)

    /**
     * Sync is off until the user turns it on in Settings: no Sync Code is
     * generated and nothing is polled or sent before that.
     */
    fun init(context: Context) {
        if (!BuildConfig.ENABLE_CLOUD_FEATURES) return
        _syncCodeFlow.value = getSyncId(context) ?: NO_CODE
    }

    fun enable(context: Context) {
        if (!BuildConfig.ENABLE_CLOUD_FEATURES) return
        val p = prefs(context)
        val id = p.getString(PREFS_KEY_SYNC_ID, null)
            ?: UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        p.edit().putBoolean(PREFS_KEY_SYNC_ENABLED, true).putString(PREFS_KEY_SYNC_ID, id).apply()
        _syncCodeFlow.value = id
    }

    /** Stops all sync traffic and forgets the local Sync Code. */
    fun disable(context: Context) {
        prefs(context).edit().putBoolean(PREFS_KEY_SYNC_ENABLED, false).remove(PREFS_KEY_SYNC_ID).apply()
        _syncCodeFlow.value = NO_CODE
    }

    fun getSyncCode(context: Context): String = getSyncId(context) ?: NO_CODE

    private fun getSyncId(context: Context): String? {
        val p = prefs(context)
        return if (p.getBoolean(PREFS_KEY_SYNC_ENABLED, false)) p.getString(PREFS_KEY_SYNC_ID, null) else null
    }

    // ── Poll ALL laptop counts (keyed by date) — for chart history ───────────
    @OptIn(ExperimentalCoroutinesApi::class)
    fun laptopHistoryFlow(context: Context): Flow<Map<String, Int>> =
        _syncCodeFlow.flatMapLatest { syncId ->
            if (syncId == NO_CODE) flowOf(emptyMap<String, Int>()) else flow {
                while (true) {
                    val rows = getRows("sync_state?sync_id=eq.${encode(syncId)}&select=date,laptop_count")
                    val map = rows.associate { it.getString("date") to it.optInt("laptop_count") }
                        .filterValues { it > 0 }
                    emit(map)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

    // ── Poll laptop_count for a single date ───────────────────────────────────
    @OptIn(ExperimentalCoroutinesApi::class)
    fun laptopCountFlow(context: Context, date: String): Flow<Int> =
        _syncCodeFlow.flatMapLatest { syncId ->
            if (syncId == NO_CODE) flowOf(0) else flow {
                while (true) {
                    val rows = getRows("sync_state?sync_id=eq.${encode(syncId)}&date=eq.$date&select=laptop_count")
                    emit(rows.firstOrNull()?.optInt("laptop_count") ?: 0)
                    delay(POLL_INTERVAL_MS)
                }
            }
        }

    // ── Push today's phone count (called from ResetWorker + onStop) ──────────
    // Upserts only the phone_* columns — PostgREST's merge-duplicates
    // resolution only overwrites columns present in the payload, so
    // laptop_count/laptop_domains are never clobbered (same merge semantics
    // as Firebase's PATCH-to-a-subpath).
    fun pushPhoneCount(context: Context, date: String, phoneCount: Int) {
        val syncId = getSyncId(context) ?: return
        val body = JSONArray().put(
            JSONObject()
                .put("sync_id", syncId)
                .put("date", date)
                .put("phone_count", phoneCount)
                .put("phone_last_updated", nowIso())
        )
        scope.launch { upsert("sync_state?on_conflict=sync_id,date", body.toString()) }
    }

    // ── REST helpers ───────────────────────────────────────────────────────

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private suspend fun getRows(path: String): List<JSONObject> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(TelemetryConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/$path")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("apikey", TelemetryConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer ${TelemetryConfig.SUPABASE_ANON_KEY}")
            }
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private fun upsert(path: String, jsonArrayBody: String) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(TelemetryConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/$path")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", TelemetryConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer ${TelemetryConfig.SUPABASE_ANON_KEY}")
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            }
            conn.outputStream.use { it.write(jsonArrayBody.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            conn?.disconnect()
        }
    }
}
