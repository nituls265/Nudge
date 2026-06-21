-- ============================================================================
-- Nudge — retention analytics views
-- Migration: 20260621233623_retention_views.sql
--
-- Creates three read-only views in the `analytics` schema over public.events.
-- Views are deliberately NOT in public so they are not exposed via the
-- Supabase auto-generated REST API.  The SQL editor (service_role) can read
-- them; anon/authenticated roles cannot.
--
-- Safe to re-run: CREATE SCHEMA IF NOT EXISTS + CREATE OR REPLACE VIEW.
-- Does NOT modify public.events.
-- ============================================================================

-- ── Schema ───────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS analytics;

-- Lock down the schema itself: no public access.
REVOKE ALL ON SCHEMA analytics FROM PUBLIC, anon, authenticated;
GRANT  USAGE ON SCHEMA analytics TO service_role, postgres;


-- ============================================================================
-- VIEW 1: analytics.v_retention_cohorts
--
-- One row per cohort date. Cohort = the earliest day_active date per install
-- (device-local calendar, matching how activity is recorded).  D1/D7/D30
-- retention counts installs that have a day_active row dated exactly
-- cohort_date + 1/7/30.
--
-- NOTE: these are bounded day-N definitions (exact calendar day, not a
-- window).  They will look noisy at small cohort sizes (n < 20) — interpret
-- percentages with caution.
-- ============================================================================

CREATE OR REPLACE VIEW analytics.v_retention_cohorts AS
WITH cohorts AS (
    -- One row per install: its first local-calendar active date.
    -- Derived from day_active rows (not first_open.timestamp_utc) so cohort
    -- date and activity date share the same device-local calendar.
    SELECT install_id,
           MIN(event_date) AS cohort_date
    FROM   public.events
    WHERE  event_type = 'day_active'
    GROUP  BY install_id
),
activity AS (
    SELECT DISTINCT install_id, event_date
    FROM   public.events
    WHERE  event_type = 'day_active'
),
retention AS (
    SELECT
        c.cohort_date,
        COUNT(DISTINCT c.install_id)                                                    AS cohort_size,
        COUNT(DISTINCT a.install_id) FILTER (WHERE a.event_date = c.cohort_date + 1)   AS d1_retained,
        COUNT(DISTINCT a.install_id) FILTER (WHERE a.event_date = c.cohort_date + 7)   AS d7_retained,
        COUNT(DISTINCT a.install_id) FILTER (WHERE a.event_date = c.cohort_date + 30)  AS d30_retained
    FROM  cohorts c
    LEFT  JOIN activity a ON a.install_id = c.install_id
    GROUP BY c.cohort_date
)
SELECT
    cohort_date,
    cohort_size,
    d1_retained,
    ROUND(100.0 * d1_retained  / NULLIF(cohort_size, 0), 1) AS d1_pct,
    d7_retained,
    ROUND(100.0 * d7_retained  / NULLIF(cohort_size, 0), 1) AS d7_pct,
    d30_retained,
    ROUND(100.0 * d30_retained / NULLIF(cohort_size, 0), 1) AS d30_pct
FROM  retention
ORDER BY cohort_date;


-- ============================================================================
-- VIEW 2: analytics.v_active_users
--
-- One row per calendar date (last 60 days of day_active data).
-- dau = distinct installs active on that exact date
-- wau = distinct installs active in [date-6 .. date]  (trailing 7 days)
-- mau = distinct installs active in [date-29 .. date] (trailing 30 days)
-- ============================================================================

CREATE OR REPLACE VIEW analytics.v_active_users AS
WITH dates AS (
    -- Distinct dates that appear in the last 60 days of activity.
    SELECT DISTINCT event_date AS d
    FROM   public.events
    WHERE  event_type = 'day_active'
      AND  event_date >= CURRENT_DATE - 59
),
activity AS (
    SELECT DISTINCT install_id, event_date
    FROM   public.events
    WHERE  event_type = 'day_active'
)
SELECT
    dates.d                                                                     AS date,
    COUNT(DISTINCT a_dau.install_id)                                            AS dau,
    COUNT(DISTINCT a_wau.install_id)                                            AS wau,
    COUNT(DISTINCT a_mau.install_id)                                            AS mau
FROM  dates
LEFT  JOIN activity a_dau ON a_dau.event_date  = dates.d
LEFT  JOIN activity a_wau ON a_wau.event_date  BETWEEN dates.d - 6  AND dates.d
LEFT  JOIN activity a_mau ON a_mau.event_date  BETWEEN dates.d - 29 AND dates.d
GROUP BY dates.d
ORDER BY dates.d;


-- ============================================================================
-- VIEW 3: analytics.v_stickiness
--
-- DAU / MAU ratio per date, expressed as a percentage.
-- Returns NULL (not an error) when MAU is 0.
-- ============================================================================

CREATE OR REPLACE VIEW analytics.v_stickiness AS
SELECT
    date,
    dau,
    mau,
    ROUND(100.0 * dau / NULLIF(mau, 0), 1) AS dau_mau_pct
FROM  analytics.v_active_users
ORDER BY date;


-- ── Permissions on views ─────────────────────────────────────────────────────
-- Revoke from broad roles first, then grant to privileged roles only.

REVOKE ALL ON analytics.v_retention_cohorts FROM PUBLIC, anon, authenticated;
REVOKE ALL ON analytics.v_active_users       FROM PUBLIC, anon, authenticated;
REVOKE ALL ON analytics.v_stickiness         FROM PUBLIC, anon, authenticated;

GRANT SELECT ON analytics.v_retention_cohorts TO service_role, postgres;
GRANT SELECT ON analytics.v_active_users       TO service_role, postgres;
GRANT SELECT ON analytics.v_stickiness         TO service_role, postgres;
