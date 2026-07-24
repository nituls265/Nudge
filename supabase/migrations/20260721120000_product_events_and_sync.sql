-- ============================================================================
-- Nudge — product analytics events + phone⇄laptop scroll sync (Supabase)
-- Replaces Firebase Analytics and the Firebase Realtime Database sync path
-- (users/{syncId}/...) so the app ships with no proprietary Google SDKs
-- (F-Droid requirement). Run once in the Supabase SQL editor.
-- ============================================================================

-- ── Product analytics events ────────────────────────────────────────────────
-- Same anonymous install_id as public.events (telemetry_schema.sql), gated by
-- the same opt-in consent flow. metadata carries the event-specific payload so
-- every row has the same top-level key set (required for the PostgREST batch
-- insert — see telemetry/core/TelemetryEvent.kt).
create table if not exists public.product_events (
    id            bigint generated always as identity primary key,
    event_type    text        not null check (event_type in (
        'daily_scroll_summary',
        'feature_bubble_toggled',
        'action_pause_toggled',
        'action_manual_reset',
        'scroll_session_snapshot',
        'intervention_triggered',
        'intervention_response'
    )),
    install_id    text        not null,
    app_version   text,
    metadata      jsonb       not null default '{}'::jsonb,
    inserted_at   timestamptz not null default now()
);

create index if not exists product_events_type_install_idx
    on public.product_events (event_type, install_id);

alter table public.product_events enable row level security;

grant insert on table public.product_events to anon;
-- Deliberately no SELECT grant — the client never reads, matching public.events.

drop policy if exists product_events_anon_insert on public.product_events;
create policy product_events_anon_insert
    on public.product_events
    for insert
    to anon
    with check (true);

-- ── Phone ⇄ laptop scroll sync ──────────────────────────────────────────────
-- Replaces Firebase Realtime Database's users/{syncId}/{phone,laptop}/{date}
-- path. sync_id is a random 12-char code generated on-device (see
-- SyncManager.kt) and pasted into the Chrome extension. Same trust model as
-- before: whoever holds the code can read/write those rows — the phone and
-- extension REST calls were always unauthenticated, so the code itself (not
-- Firebase Auth) was already the only thing gating access.
create table if not exists public.sync_state (
    sync_id             text        not null,
    date                date        not null,
    phone_count         integer     not null default 0,
    phone_last_updated  timestamptz,
    laptop_count        integer     not null default 0,
    laptop_domains      jsonb       not null default '{}'::jsonb,
    laptop_synced_at    timestamptz,
    primary key (sync_id, date)
);

create index if not exists sync_state_sync_id_idx on public.sync_state (sync_id);

alter table public.sync_state enable row level security;

grant select, insert, update on table public.sync_state to anon;

drop policy if exists sync_state_anon_select on public.sync_state;
create policy sync_state_anon_select on public.sync_state
    for select to anon using (true);

drop policy if exists sync_state_anon_insert on public.sync_state;
create policy sync_state_anon_insert on public.sync_state
    for insert to anon with check (true);

drop policy if exists sync_state_anon_update on public.sync_state;
create policy sync_state_anon_update on public.sync_state
    for update to anon using (true) with check (true);
