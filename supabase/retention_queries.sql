-- ============================================================================
-- Nudge — retention analytics over public.events
-- Run in the Supabase SQL editor (service_role bypasses RLS).
--
-- Conventions:
--   • cohort        = the install's FIRST local active day (its first_open day).
--                     Computed from event_date (local) so cohort and activity
--                     share one calendar — avoids a UTC-vs-local off-by-one.
--   • "DN retention" = % of a cohort that has a day_active EXACTLY N days later
--                      (classic day-N retention).
--   • All activity uses day_active.event_date, the device-LOCAL calendar date,
--     so a "day" matches how the user experiences it.
-- ============================================================================


-- ── Shared building blocks ──────────────────────────────────────────────────
-- cohorts : one row per install, its first_open date
-- activity: distinct (install, active local date)

-- ----------------------------------------------------------------------------
-- 1) RETENTION BY COHORT — D1 / D7 / D30
-- ----------------------------------------------------------------------------
with cohorts as (
    -- first local active day per install = its first_open day, in the activity
    -- calendar (local). first_open + the first day_active are queued together on
    -- opt-in, so min(event_date) is the day the user first opened the app.
    select install_id, min(event_date) as cohort_date
    from public.events
    where event_type = 'day_active'
    group by install_id
),
activity as (
    select distinct install_id, event_date
    from public.events
    where event_type = 'day_active'
),
retention as (
    select
        c.cohort_date,
        count(distinct c.install_id)                                                       as cohort_size,
        count(distinct a.install_id) filter (where a.event_date = c.cohort_date + 1)        as d1,
        count(distinct a.install_id) filter (where a.event_date = c.cohort_date + 7)        as d7,
        count(distinct a.install_id) filter (where a.event_date = c.cohort_date + 30)       as d30
    from cohorts c
    left join activity a on a.install_id = c.install_id
    group by c.cohort_date
)
select
    cohort_date,
    cohort_size,
    d1, d7, d30,
    round(100.0 * d1  / nullif(cohort_size, 0), 1) as d1_pct,
    round(100.0 * d7  / nullif(cohort_size, 0), 1) as d7_pct,
    round(100.0 * d30 / nullif(cohort_size, 0), 1) as d30_pct
from retention
order by cohort_date;


-- ----------------------------------------------------------------------------
-- 2) DAU / WAU / MAU + STICKINESS (as of today, device-local dates)
--    DAU  = active today
--    WAU  = active in the last 7 days
--    MAU  = active in the last 30 days
--    stickiness = DAU / MAU
-- ----------------------------------------------------------------------------
select
    count(distinct install_id) filter (where event_date  = current_date)        as dau,
    count(distinct install_id) filter (where event_date >= current_date - 6)    as wau,
    count(distinct install_id) filter (where event_date >= current_date - 29)   as mau,
    round(
        100.0 * count(distinct install_id) filter (where event_date = current_date)
        / nullif(count(distinct install_id) filter (where event_date >= current_date - 29), 0),
        1
    ) as stickiness_dau_mau_pct
from public.events
where event_type = 'day_active';


-- ----------------------------------------------------------------------------
-- 3) DAILY ACTIVE USERS — time series (handy for a chart)
-- ----------------------------------------------------------------------------
select event_date, count(distinct install_id) as dau
from public.events
where event_type = 'day_active'
group by event_date
order by event_date;


-- ----------------------------------------------------------------------------
-- 4) UNIQUE INSTALLS — distinct install IDs with a first_open event
--    Counts opted-in telemetry installs only (the GitHub/F-Droid builds ship
--    without analytics), so this is a lower bound on real installs.
--    A reinstall or clear-data mints a NEW install_id, so it counts again.
-- ----------------------------------------------------------------------------
select
    count(distinct install_id) as unique_installs,
    min(timestamp_utc)         as first_install_at,
    max(timestamp_utc)         as latest_install_at
from public.events
where event_type = 'first_open';

-- Optional: split by platform and app version
select
    platform,
    app_version,
    count(distinct install_id) as unique_installs
from public.events
where event_type = 'first_open'
group by platform, app_version
order by unique_installs desc;
