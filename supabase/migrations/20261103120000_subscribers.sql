-- 30-day challenge sign-ups from the landing page's post-download panel.
--
-- Separate from `waitlist` (iPhone launch) and from all telemetry tables;
-- nothing here can be joined to app usage. Access for the public (anon) role,
-- i.e. the publishable key in docs/config.js:
--   * INSERT only, and only the `email` and `wants_updates` columns.
--   * No SELECT / UPDATE / DELETE, not even on its own rows (the site posts
--     with `Prefer: return=minimal`).
--   * A duplicate address (any letter case) fails with a unique violation
--     (HTTP 409); the site shows the same success message either way, so it
--     never reveals whether an address is already signed up.
-- You read and update rows from the Supabase dashboard; the saved queries
-- are in supabase/queries/subscribers.sql.

create table if not exists public.subscribers (
    id              uuid        primary key default gen_random_uuid(),
    email           text        not null,
    -- Separate, unticked-by-default opt-in for "updates and articles,
    -- a few times a year". The day-30 check-in is what signing up is for.
    wants_updates   boolean     not null default false,
    source          text        not null default 'android_download',
    created_at      timestamptz not null default now(),
    guide_queued_at timestamptz,           -- startup guide handed to the mailer (automatic, if set up)
    checkin_sent_at timestamptz,           -- day-30 check-in email (sent by hand)
    unsubscribed_at timestamptz,           -- replied STOP: send nothing more
    constraint subscribers_email_length check (char_length(email) between 3 and 254),
    constraint subscribers_email_format check (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    constraint subscribers_source check (source in ('android_download'))
);

create unique index if not exists subscribers_email_lower_key
    on public.subscribers (lower(email));

alter table public.subscribers enable row level security;

revoke all on table public.subscribers from public, anon, authenticated;
grant insert (email, wants_updates) on table public.subscribers to anon;

drop policy if exists subscribers_anon_insert on public.subscribers;
create policy subscribers_anon_insert
    on public.subscribers
    for insert
    to anon
    with check (true);
