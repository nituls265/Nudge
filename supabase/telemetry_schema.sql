-- ============================================================================
-- Nudge — Phase 0 retention telemetry: Supabase schema
-- Run once in the Supabase SQL editor.
--
-- One table, written by the app's anon key (INSERT only via RLS). The client
-- never reads. Two event types only: first_open and day_active.
-- ============================================================================

create table if not exists public.events (
    id             bigint generated always as identity primary key,
    event_type     text        not null check (event_type in ('first_open', 'day_active')),
    install_id     text        not null,          -- anonymous random UUID from the device
    app_version    text,
    platform       text,                          -- set on first_open ('android' / 'ios')
    timestamp_utc  timestamptz,                   -- set on first_open
    event_date     date,                          -- set on day_active (device-LOCAL calendar date)
    inserted_at    timestamptz not null default now()
);

-- Indexes for the retention / DAU queries.
create index if not exists events_type_install_idx on public.events (event_type, install_id);
create index if not exists events_day_active_idx   on public.events (event_date) where event_type = 'day_active';
create index if not exists events_first_open_idx   on public.events (install_id) where event_type = 'first_open';

-- ── Row-Level Security: anon may INSERT only ────────────────────────────────
alter table public.events enable row level security;

-- Privilege grant (new tables don't grant to anon by default).
grant insert on table public.events to anon;
-- Deliberately NO `grant select` to anon → the client cannot read any rows.

-- INSERT policy for the anon role. No SELECT/UPDATE/DELETE policy exists, so
-- those operations are denied for anon.
drop policy if exists events_anon_insert on public.events;
create policy events_anon_insert
    on public.events
    for insert
    to anon
    with check (true);

-- Run your retention analysis with the service_role key (server-side / SQL
-- editor), which bypasses RLS — see retention_queries.sql.
