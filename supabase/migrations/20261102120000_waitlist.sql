-- iOS waitlist for the landing page (docs/).
--
-- Deliberately separate from the telemetry tables (events, product_events,
-- sync_state): this migration does not reference, alter or grant anything on
-- them, and the website gets no read access to any table.
--
-- Access model for the public (anon) role, i.e. the publishable key shipped in
-- docs/config.js:
--   * INSERT only, and only the `email` column (id / created_at are always
--     server-generated).
--   * No SELECT, UPDATE or DELETE — not even on rows it just inserted. The
--     site therefore posts with `Prefer: return=minimal`.
--   * A duplicate address raises a unique violation (HTTP 409). The site
--     shows the same success message for 201 and 409, so the response never
--     reveals whether an address was already on the list.
-- Read the list from the Supabase dashboard / SQL editor (service role), never
-- from client code.

create table if not exists public.waitlist (
    id         uuid        primary key default gen_random_uuid(),
    email      text        not null,
    created_at timestamptz not null default now(),
    constraint waitlist_email_length check (char_length(email) between 3 and 254),
    constraint waitlist_email_format check (email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$')
);

-- Case-insensitive uniqueness: Foo@x.com and foo@x.com are the same person.
create unique index if not exists waitlist_email_lower_key
    on public.waitlist (lower(email));

alter table public.waitlist enable row level security;

-- Start from zero privileges for the API roles, then grant back exactly one.
revoke all on table public.waitlist from public, anon, authenticated;
grant insert (email) on table public.waitlist to anon;

drop policy if exists waitlist_anon_insert on public.waitlist;
create policy waitlist_anon_insert
    on public.waitlist
    for insert
    to anon
    with check (true);
