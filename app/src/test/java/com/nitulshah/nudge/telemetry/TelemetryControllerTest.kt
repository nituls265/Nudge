package com.nitulshah.nudge.telemetry

import com.nitulshah.nudge.telemetry.android.AndroidInstallIdGenerator
import com.nitulshah.nudge.telemetry.core.InstallIdGenerator
import com.nitulshah.nudge.telemetry.core.TelemetryClock
import com.nitulshah.nudge.telemetry.core.TelemetryController
import com.nitulshah.nudge.telemetry.core.TelemetryStorage
import com.nitulshah.nudge.telemetry.core.TelemetryTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification tests for the platform-agnostic telemetry core (no real Supabase,
 * no Android — in-memory fakes only). Lives in app/src/test because this project
 * is single-module Android; the code under test is the pure `telemetry.core`
 * logic that would sit in commonMain in a KMP build.
 */
class TelemetryControllerTest {

    // ── In-memory fakes ──────────────────────────────────────────────────────

    /** A FreshStorage instance == a fresh install; reusing one == an app restart. */
    private class FakeStorage : TelemetryStorage {
        private var optedIn = false
        private var answered = false
        private var installId: String? = null
        private var firstOpenSent = false
        private var lastActiveDate: String? = null
        private val queue = mutableListOf<String>()

        override suspend fun isOptedIn() = optedIn
        override suspend fun setOptedIn(value: Boolean) { optedIn = value }
        override suspend fun hasAnsweredConsent() = answered
        override suspend fun setConsentAnswered(value: Boolean) { answered = value }
        override suspend fun installId() = installId
        override suspend fun setInstallId(id: String) { installId = id }
        override suspend fun clearInstallId() { installId = null }
        override suspend fun firstOpenSent() = firstOpenSent
        override suspend fun setFirstOpenSent(value: Boolean) { firstOpenSent = value }
        override suspend fun lastActiveDate() = lastActiveDate
        override suspend fun setLastActiveDate(date: String) { lastActiveDate = date }
        override suspend fun clearLastActiveDate() { lastActiveDate = null }
        override suspend fun queuedEvents() = queue.toList()
        override suspend fun appendQueuedEvent(json: String) { queue.add(json) }
        override suspend fun clearQueue() { queue.clear() }
    }

    /** Mock sink. online=false simulates offline (POST fails, nothing delivered). */
    private class RecordingTransport(var online: Boolean = true) : TelemetryTransport {
        val sentBatches = mutableListOf<String>()   // each = a JSON array actually "delivered"
        var postCalls = 0; private set
        override suspend fun postEvents(jsonArrayBody: String): Boolean {
            postCalls++
            if (!online) return false
            sentBatches.add(jsonArrayBody)
            return true
        }
        fun deliveredCountOf(eventType: String): Int =
            sentBatches.sumOf { Regex("\"event_type\":\"$eventType\"").findAll(it).count() }
        fun deliveredJson(): String = sentBatches.joinToString(separator = "")
    }

    private class FakeClock(
        var localDate: String,
        var utc: String = "2026-01-01T00:00:00Z",
    ) : TelemetryClock {
        override fun nowUtcIso() = utc
        override fun todayLocalDate() = localDate
    }

    private fun controller(
        storage: FakeStorage,
        transport: RecordingTransport,
        clock: FakeClock,
        idGen: InstallIdGenerator = AndroidInstallIdGenerator(), // the real one-liner: UUID.randomUUID()
    ) = TelemetryController(
        storage = storage, transport = transport, clock = clock,
        idGenerator = idGen, appVersion = "1.0-test", platform = "android",
    )

    private val UUID_V4 =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    // ── 1. Install ID: valid random UUID, stable across restart, regenerated on clear-data ──

    @Test
    fun installId_isValidRandomUuidV4() {
        val gen = AndroidInstallIdGenerator()
        val a = gen.newId(); val b = gen.newId()
        assertTrue("not a valid UUID v4: $a", UUID_V4.matches(a))
        assertTrue("not a valid UUID v4: $b", UUID_V4.matches(b))
        assertNotEquals("install ids must be random / distinct", a, b)
    }

    @Test
    fun installId_stableAcrossRestart_regeneratedAfterClearData() = runTest {
        val storage = FakeStorage()
        controller(storage, RecordingTransport(), FakeClock("2026-01-01")).optIn()
        val id1 = storage.installId()
        assertNotNull("id created on opt-in", id1)
        assertTrue(UUID_V4.matches(id1!!))

        // Simulated app restart: NEW controller over the SAME storage.
        controller(storage, RecordingTransport(), FakeClock("2026-01-02")).recordAppOpen()
        assertEquals("install id must survive a restart", id1, storage.installId())

        // Simulated clear-data / reinstall: a brand-new storage.
        val fresh = FakeStorage()
        controller(fresh, RecordingTransport(), FakeClock("2026-01-03")).optIn()
        val id2 = fresh.installId()
        assertNotNull(id2)
        assertNotEquals("clear-data / reinstall must mint a NEW id", id1, id2)
    }

    // ── 2. day_active dedupe ─────────────────────────────────────────────────

    @Test
    fun dayActive_oncePerLocalDay_newDayAddsOne() = runTest {
        val storage = FakeStorage(); val sink = RecordingTransport(); val clock = FakeClock("2026-01-01")
        val c = controller(storage, sink, clock)

        c.optIn()            // day 1: first_open + day_active(2026-01-01)
        c.recordAppOpen()    // same day → no new day_active
        c.recordAppOpen()    // same day → no new day_active
        assertEquals("exactly ONE day_active for 2026-01-01", 1, sink.deliveredCountOf("day_active"))

        clock.localDate = "2026-01-02"
        c.recordAppOpen()    // new day → exactly one more
        assertEquals("a new day queues a new day_active", 2, sink.deliveredCountOf("day_active"))
        assertTrue(sink.deliveredJson().contains("\"event_date\":\"2026-01-01\""))
        assertTrue(sink.deliveredJson().contains("\"event_date\":\"2026-01-02\""))
    }

    // ── 3. first_open fires exactly once, ever ───────────────────────────────

    @Test
    fun firstOpen_firesExactlyOnce_acrossDaysAndRestarts() = runTest {
        val storage = FakeStorage(); val sink = RecordingTransport(); val clock = FakeClock("2026-01-01")
        controller(storage, sink, clock).optIn()               // day 1
        controller(storage, sink, clock).recordAppOpen()       // restart, same day
        clock.localDate = "2026-01-02"
        controller(storage, sink, clock).recordAppOpen()       // day 2
        clock.localDate = "2026-01-03"
        controller(storage, sink, clock).recordAppOpen()       // day 3

        assertEquals("first_open exactly once, ever", 1, sink.deliveredCountOf("first_open"))
        assertEquals("day_active still accrues per day", 3, sink.deliveredCountOf("day_active"))
    }

    // ── 4. Opt-out deletes id + stops queueing; opt-in mints a NEW id ────────

    @Test
    fun optOut_deletesId_stopsTracking_optInMintsNewId() = runTest {
        val storage = FakeStorage(); val sink = RecordingTransport(); val clock = FakeClock("2026-01-01")
        val c = controller(storage, sink, clock)

        c.optIn()
        val id1 = storage.installId(); assertNotNull(id1)

        c.optOut()
        assertNull("opt-out DELETES the local install id", storage.installId())
        assertFalse("opt-out turns telemetry off", storage.isOptedIn())
        assertTrue("opt-out clears the queue", storage.queuedEvents().isEmpty())

        // While opted out, tracking is a no-op (nothing queued/sent).
        clock.localDate = "2026-01-02"
        val tracked = c.recordAppOpen()
        assertFalse("recordAppOpen no-ops while opted out", tracked)
        assertTrue("nothing queued while opted out", storage.queuedEvents().isEmpty())

        // Opt back in → a brand-new id (NO resurrection of the old one).
        c.optIn()
        val id2 = storage.installId(); assertNotNull(id2)
        assertNotEquals("opt-in after opt-out must NOT resurrect the old id", id1, id2)
        assertTrue(UUID_V4.matches(id2!!))
    }

    // ── 5. Offline queue + single flush, no duplicates ───────────────────────

    @Test
    fun offline_queues_thenFlushesOnce_withNoDuplicates() = runTest {
        val storage = FakeStorage(); val sink = RecordingTransport(online = false); val clock = FakeClock("2026-01-01")
        val c = controller(storage, sink, clock)

        c.optIn()  // offline: queues first_open + day_active(day1); flush fails
        assertEquals("events queued while offline", 2, storage.queuedEvents().size)
        assertEquals("nothing delivered while offline", 0, sink.sentBatches.size)

        c.recordAppOpen()  // same day, still offline → dedupe, no growth, no dups
        assertEquals("no duplicate growth same day", 2, storage.queuedEvents().size)

        clock.localDate = "2026-01-02"
        c.recordAppOpen()  // new day, still offline → +1
        assertEquals(3, storage.queuedEvents().size)

        // Network returns → one flush delivers everything, exactly once.
        sink.online = true
        assertTrue(c.flush())
        assertTrue("queue drained after successful flush", storage.queuedEvents().isEmpty())
        assertEquals("first_open delivered exactly once", 1, sink.deliveredCountOf("first_open"))
        assertEquals("both day_active delivered, no dups", 2, sink.deliveredCountOf("day_active"))

        // Flushing again must not double-send.
        val batchesBefore = sink.sentBatches.size
        c.flush()
        assertEquals("no double-send on an empty queue", batchesBefore, sink.sentBatches.size)
    }

    // ── Bonus: payload shape + no PII in what actually leaves ────────────────

    @Test
    fun payloads_haveExpectedFields_andNoForbiddenIdentifiers() = runTest {
        val storage = FakeStorage(); val sink = RecordingTransport()
        controller(storage, sink, FakeClock(localDate = "2026-01-05", utc = "2026-01-05T09:00:00Z")).optIn()
        val json = sink.deliveredJson()

        assertTrue(json.contains("\"event_type\":\"first_open\""))
        assertTrue(json.contains("\"platform\":\"android\""))
        assertTrue(json.contains("\"timestamp_utc\":\"2026-01-05T09:00:00Z\""))
        assertTrue(json.contains("\"event_type\":\"day_active\""))
        assertTrue(json.contains("\"event_date\":\"2026-01-05\""))

        listOf("android_id", "advertising", "imei", "macaddress", "serial",
               "latitude", "longitude", "email", "phone").forEach {
            assertFalse("payload must never contain '$it'", json.lowercase().contains(it))
        }
    }
}
