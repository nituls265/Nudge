package com.example.nudgev0.telemetry

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
    const val SUPABASE_URL = ""       // e.g. "https://abcdxyz.supabase.co"
    const val SUPABASE_ANON_KEY = ""  // e.g. "eyJhbGciOiJIUzI1NiIsInR5cCI6..."
}
