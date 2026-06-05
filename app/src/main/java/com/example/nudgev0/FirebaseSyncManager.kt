package com.example.nudgev0

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

object FirebaseSyncManager {

    private val auth = FirebaseAuth.getInstance()
    private val db   = FirebaseDatabase.getInstance()

    // ── Reactive sync code — emits "—" until auth completes ──────────────────
    private val _syncCodeFlow = MutableStateFlow("—")
    val syncCodeFlow: StateFlow<String> = _syncCodeFlow

    // ── Init: sign in anonymously once, store the UID as the Sync Code ────────
    //
    // The Sync Code is the FULL Firebase UID — it doubles as the Realtime
    // Database path key (users/{uid}/…) shared between phone and the Chrome
    // extension. We deliberately do NOT truncate or upper-case it:
    //   • take(12)     dropped Firebase's per-account uniqueness guarantee.
    //   • .uppercase() collapsed mixed-case UIDs (e.g. "aB…" and "Ab…") onto
    //                  the same key, aliasing otherwise-distinct accounts.
    // Both together made the keyspace 36^12 (birthday collisions ~2.2B users).
    // Firebase UIDs are already URL/key-safe and globally unique, so using the
    // full UID gives zero-collision sync. (The code is copy-pasted, so the
    // extra length is invisible to users.)
    suspend fun init(context: Context) {
        try {
            // If we already stored a UID from a previous launch, emit it immediately
            val prefs = context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            val stored = prefs.getString("FIREBASE_SYNC_ID", null)
            if (stored != null) _syncCodeFlow.value = stored

            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
            val uid = auth.currentUser?.uid ?: return
            prefs.edit().putString("FIREBASE_SYNC_ID", uid).apply()
            _syncCodeFlow.value = uid
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ── Sync Code: the full UID, displayed in the app and pasted into the extension
    fun getSyncCode(context: Context): String {
        return context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .getString("FIREBASE_SYNC_ID", null) ?: "—"
    }

    // Same value the UI shows — this is exactly what the extension pastes in
    private fun getSyncId(context: Context): String? {
        return context.getSharedPreferences("NudgePrefs", Context.MODE_PRIVATE)
            .getString("FIREBASE_SYNC_ID", null)
    }

    // ── Listen to ALL laptop counts (keyed by date) — for chart history ─────────
    fun laptopHistoryFlow(context: Context): Flow<Map<String, Int>> = callbackFlow {
        val syncId = getSyncId(context) ?: run { trySend(emptyMap()); close(); return@callbackFlow }

        val ref      = db.getReference("users/$syncId/laptop")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val map = mutableMapOf<String, Int>()
                for (child in snapshot.children) {
                    val date  = child.key ?: continue
                    val count = child.child("laptop_count").getValue(Long::class.java)?.toInt() ?: 0
                    if (count > 0) map[date] = count
                }
                trySend(map)
            }
            override fun onCancelled(error: DatabaseError) { trySend(emptyMap()) }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }.catch { emit(emptyMap()) }

    // ── Listen to laptop_count for today in real time ─────────────────────────
    fun laptopCountFlow(context: Context, date: String): Flow<Int> = callbackFlow {
        val syncId = getSyncId(context) ?: run { trySend(0); close(); return@callbackFlow }

        val ref      = db.getReference("users/$syncId/laptop/$date/laptop_count")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trySend((snapshot.getValue(Long::class.java) ?: 0L).toInt())
            }
            override fun onCancelled(error: DatabaseError) {
                trySend(0)
            }
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }.catch { emit(0) }

    // ── Push today's phone count (called from ResetWorker + onStop) ──────────
    // Uses a map update so it never touches laptop_count
    fun pushPhoneCount(context: Context, date: String, phoneCount: Int) {
        val syncId = getSyncId(context) ?: return

        val ref = db.getReference("users/$syncId/phone/$date")
        ref.setValue(mapOf(
            "phone_count"        to phoneCount,
            "phone_last_updated" to System.currentTimeMillis()
        )).addOnFailureListener { it.printStackTrace() }
    }
}
