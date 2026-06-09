-- ============================================================================
-- Nudge — retention analytics over public.events
-- Run in the Supabase SQL editor (service_role bypasses RLS).
--
-- Conventions:
--   • cohort        = the calendar date of an install's first_open.
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
    select install_id, min(timestamp_utc)::date as cohort_date
    from public.events
    where event_type = 'first_open'
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
