package com.example.nudgev0

import android.content.Context
import com.example.nudgev0.telemetry.TelemetryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _syncCodeFlow = MutableStateFlow("—")
    val syncCodeFlow: StateFlow<String> = _syncCodeFlow

    /** Generates the local Sync Code on first call; otherwise just loads it. */
    fun init(context: Context) {
        if (!BuildConfig.ENABLE_CLOUD_FEATURES) return
        val prefs = context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
        var id = prefs.getString(PREFS_KEY_SYNC_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
            prefs.edit().putString(PREFS_KEY_SYNC_ID, id).apply()
        }
        _syncCodeFlow.value = id
    }

    // ── Sync Code: shown in the app and pasted into the extension ────────────
    fun getSyncCode(context: Context): String =
        context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .getString(PREFS_KEY_SYNC_ID, null) ?: "—"

    private fun getSyncId(context: Context): String? =
        context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .getString(PREFS_KEY_SYNC_ID, null)

    // ── Poll ALL laptop counts (keyed by date) — for chart history ───────────
    fun laptopHistoryFlow(context: Context): Flow<Map<String, Int>> = flow {
        val syncId = getSyncId(context) ?: run { emit(emptyMap()); return@flow }
        while (true) {
            val rows = getRows("sync_state?sync_id=eq.${encode(syncId)}&select=date,laptop_count")
            val map = rows.associate { it.getString("date") to it.optInt("laptop_count") }
                .filterValues { it > 0 }
            emit(map)
            delay(POLL_INTERVAL_MS)
        }
    }

    // ── Poll laptop_count for a single date ───────────────────────────────────
    fun laptopCountFlow(context: Context, date: String): Flow<Int> = flow {
        val syncId = getSyncId(context) ?: run { emit(0); return@flow }
        while (true) {
            val rows = getRows("sync_state?sync_id=eq.${encode(syncId)}&date=eq.$date&select=laptop_count")
            emit(rows.firstOrNull()?.optInt("laptop_count") ?: 0)
            delay(POLL_INTERVAL_MS)
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
