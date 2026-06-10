-- ============================================================================
-- Nudge — SELF-ASSERTING validation of the retention SQL against synthetic data.
--
-- Safe to run in the Supabase SQL editor: it uses a SEPARATE `events_test` table,
-- NEVER touches production `public.events`, and DROPS itself at the end. If every
-- number is correct it prints "✅ ALL RETENTION ASSERTIONS PASSED"; otherwise it
-- RAISES an exception naming the mismatch (fail-loud).
--
-- The query logic below is character-identical to retention_queries.sql, with the
-- table name swapped to events_test.
--
-- Synthetic design — 8 installs, offsets in days relative to T = current_date:
--   i1  first_open T-40, active {T-40,T-39,T-33,T-10}
--   i2  first_open T-40, active {T-40,T-39}
--   i3  first_open T-40, active {T-40}
--   i4  first_open T-8,  active {T-8,T-7,T-1}
--   i5  first_open T-8,  active {T-8}
--   i6  first_open T-1,  active {T-1,T}
--   i7  first_open T,    active {T}
--   i8  first_open T,    active {T}
--
-- EXPECTED (independently verified):
--   Retention by cohort:
--     T-40 : size 3, D1=2 (66.7%), D7=1 (33.3%), D30=1 (33.3%)
--     T-8  : size 2, D1=1 (50.0%), D7=1 (50.0%), D30=0 (0.0%)
--     T-1  : size 1, D1=1 (100%),  D7=0,         D30=0
--     T    : size 2, D1=0,         D7=0,         D30=0
--   DAU=3, WAU=4, MAU=6, stickiness (DAU/MAU)=50.0%
-- ============================================================================

set timezone = 'UTC';   -- keep first_open(UTC) and event_date aligned in the test

drop table if exists events_test cascade;
create table events_test (
    id             bigint generated always as identity primary key,
    event_type     text not null check (event_type in ('first_open','day_active')),
    install_id     text not null,
    app_version    text,
    platform       text,
    timestamp_utc  timestamptz,
    event_date     date,
    inserted_at    timestamptz not null default now()
);

-- first_open (one per install, at noon UTC of the cohort date)
insert into events_test (event_type, install_id, app_version, platform, timestamp_utc) values
 ('first_open','i1','1.0-test','android',(current_date-40)::timestamptz + interval '12 hours'),
 ('first_open','i2','1.0-test','android',(current_date-40)::timestamptz + interval '12 hours'),
 ('first_open','i3','1.0-test','android',(current_date-40)::timestamptz + interval '12 hours'),
 ('first_open','i4','1.0-test','android',(current_date-8 )::timestamptz + interval '12 hours'),
 ('first_open','i5','1.0-test','android',(current_date-8 )::timestamptz + interval '12 hours'),
 ('first_open','i6','1.0-test','android',(current_date-1 )::timestamptz + interval '12 hours'),
 ('first_open','i7','1.0-test','android',(current_date   )::timestamptz + interval '12 hours'),
 ('first_open','i8','1.0-test','android',(current_date   )::timestamptz + interval '12 hours');

-- day_active (device-local calendar dates)
insert into events_test (event_type, install_id, app_version, event_date) values
 ('day_active','i1','1.0-test',current_date-40),
 ('day_active','i1','1.0-test',current_date-39),
 ('day_active','i1','1.0-test',current_date-33),
 ('day_active','i1','1.0-test',current_date-10),
 ('day_active','i2','1.0-test',current_date-40),
 ('day_active','i2','1.0-test',current_date-39),
 ('day_active','i3','1.0-test',current_date-40),
 ('day_active','i4','1.0-test',current_date-8),
 ('day_active','i4','1.0-test',current_date-7),
 ('day_active','i4','1.0-test',current_date-1),
 ('day_active','i5','1.0-test',current_date-8),
 ('day_active','i6','1.0-test',current_date-1),
 ('day_active','i6','1.0-test',current_date),
 ('day_active','i7','1.0-test',current_date),
 ('day_active','i8','1.0-test',current_date);

-- Retention view = retention_queries.sql query #1, on events_test.
create temp view v_ret as
with cohorts as (
    select install_id, min(timestamp_utc)::date as cohort_date
    from events_test
    where event_type = 'first_open'
    group by install_id
),
activity as (
    select distinct install_id, event_date
    from events_test
    where event_type = 'day_active'
)
select
    c.cohort_date,
    count(distinct c.install_id)                                                as cohort_size,
    count(distinct a.install_id) filter (where a.event_date = c.cohort_date + 1)  as d1,
    count(distinct a.install_id) filter (where a.event_date = c.cohort_date + 7)  as d7,
    count(distinct a.install_id) filter (where a.event_date = c.cohort_date + 30) as d30
from cohorts c
left join activity a on a.install_id = c.install_id
group by c.cohort_date;

do $$
declare
  c40 record; c8 record; c1 record; c0 record;
  dau int; wau int; mau int; stick numeric;
begin
  select * into c40 from v_ret where cohort_date = current_date - 40;
  select * into c8  from v_ret where cohort_date = current_date - 8;
  select * into c1  from v_ret where cohort_date = current_date - 1;
  select * into c0  from v_ret where cohort_date = current_date;

  if c40 is null or not (c40.cohort_size=3 and c40.d1=2 and c40.d7=1 and c40.d30=1) then
     raise exception 'FAIL cohort T-40 (expected size3 d1=2 d7=1 d30=1): %', row_to_json(c40); end if;
  if c8  is null or not (c8.cohort_size=2  and c8.d1=1  and c8.d7=1  and c8.d30=0) then
     raise exception 'FAIL cohort T-8 (expected size2 d1=1 d7=1 d30=0): %', row_to_json(c8); end if;
  if c1  is null or not (c1.cohort_size=1  and c1.d1=1  and c1.d7=0  and c1.d30=0) then
     raise exception 'FAIL cohort T-1 (expected size1 d1=1 d7=0 d30=0): %', row_to_json(c1); end if;
  if c0  is null or not (c0.cohort_size=2  and c0.d1=0  and c0.d7=0  and c0.d30=0) then
     raise exception 'FAIL cohort T-0 (expected size2 all 0): %', row_to_json(c0); end if;

  -- DAU/WAU/MAU = retention_queries.sql query #2, on events_test
  select
    count(distinct install_id) filter (where event_date  = current_date),
    count(distinct install_id) filter (where event_date >= current_date - 6),
    count(distinct install_id) filter (where event_date >= current_date - 29)
  into dau, wau, mau
  from events_test where event_type = 'day_active';

  if not (dau = 3 and wau = 4 and mau = 6) then
     raise exception 'FAIL DAU/WAU/MAU (expected 3/4/6): dau=% wau=% mau=%', dau, wau, mau; end if;

  stick := round(100.0 * dau / nullif(mau, 0), 1);
  if stick <> 50.0 then raise exception 'FAIL stickiness (expected 50.0): %', stick; end if;

  raise notice '✅ ALL RETENTION ASSERTIONS PASSED — cohorts D1/D7/D30 correct; DAU=3 WAU=4 MAU=6 stickiness=50%%';
end $$;

drop view if exists v_ret;
drop table if exists events_test cascade;   -- production public.events untouched
