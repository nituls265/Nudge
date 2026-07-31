package com.nitulshah.nudge.telemetry

/**
 * Supabase project endpoint + anon (publishable) key used to INSERT telemetry rows.
 *
 * The anon key is designed to ship in clients — Row-Level Security restricts it to
 * INSERT-only on the `events` table (see supabase/telemetry_schema.sql), and the
 * client never reads. It is NOT a secret.
 *
 * Replace the two placeholders below with your project's values. If left blank,
 * telemetry POSTs are skipped (events just stay queued), so the app builds and
 * runs fine without a configured backend.
 */
object TelemetryConfig {
    const val SUPABASE_URL = "https://mvfdwgcknmskadhlujgk.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im12ZmR3Z2Nrbm1za2FkaGx1amdrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEzODM3NTMsImV4cCI6MjA5Njk1OTc1M30.xN66ohuXKM6EnyYM1dt9-7ctgB29FLXFs8YeH27HqWE"
}
