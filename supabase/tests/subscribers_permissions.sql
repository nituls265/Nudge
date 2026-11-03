-- Local permission + mailer-trigger test for the 30-day-challenge tables.
-- Run against a THROWAWAY Postgres (never your Supabase project), repo root:
--   createdb nudge_subs_test
--   psql -d nudge_subs_test -X -q -f supabase/tests/subscribers_permissions.sql
-- Expect exactly 9 "ERROR:" lines (S3..S11) and a final "RESULT: PASS".
-- `net` and `vault` are stand-ins for Supabase's pg_net and Vault.
\set ON_ERROR_STOP 0

do $$ begin
    if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
end $$;
grant usage on schema public to anon, authenticated;
alter default privileges in schema public grant all on tables to anon, authenticated;

-- Stand-ins: vault.decrypted_secrets and net.http_post (records each call).
create schema if not exists vault;
create table if not exists vault.decrypted_secrets (name text primary key, decrypted_secret text);
create schema if not exists net;
create table if not exists net.calls (url text, body jsonb, headers jsonb);
create table if not exists net.fail (on_ boolean);
create or replace function net.http_post(url text, body jsonb default '{}', params jsonb default '{}',
                                         headers jsonb default '{}', timeout_milliseconds int default 5000)
returns bigint language plpgsql as $$
begin
    if exists (select 1 from net.fail where on_) then raise exception 'mailer down'; end if;
    insert into net.calls values (url, body, headers);
    return 1;
end $$;

\i supabase/migrations/20261103120000_subscribers.sql
\i supabase/migrations/20261103120100_subscribers_guide_mailer.sql
\echo '--- apply twice (idempotent)'
\i supabase/migrations/20261103120000_subscribers.sql
\i supabase/migrations/20261103120100_subscribers_guide_mailer.sql

set role anon;
\echo '--- S1 anon insert, mailer not configured (expect INSERT, no mail)'
insert into public.subscribers (email) values ('a@example.com');
\echo '--- S2 anon insert with updates opt-in (expect INSERT)'
insert into public.subscribers (email, wants_updates) values ('b@example.com', true);
\echo '--- S3 duplicate, different case (expect unique violation)'
insert into public.subscribers (email) values ('A@Example.COM');
\echo '--- S4 invalid email (expect check violation)'
insert into public.subscribers (email) values ('nope');
\echo '--- S5 anon select (expect permission denied)'
select * from public.subscribers;
\echo '--- S6 anon update (expect permission denied)'
update public.subscribers set wants_updates = true;
\echo '--- S7 anon delete (expect permission denied)'
delete from public.subscribers;
\echo '--- S8 anon sets a server-only column (expect permission denied)'
insert into public.subscribers (email, guide_queued_at) values ('c@example.com', now());
\echo '--- S9 insert ... returning (expect permission denied)'
insert into public.subscribers (email) values ('d@example.com') returning id;
\echo '--- S10 anon calls the trigger function directly (expect permission denied)'
select public.subscribers_send_guide();
reset role;

set role authenticated;
\echo '--- S11 authenticated insert (expect permission denied)'
insert into public.subscribers (email) values ('g@example.com');
reset role;

-- Configure the mailer, then a new sign-up must POST exactly once.
insert into vault.decrypted_secrets values
    ('nudge_mailer_url', 'https://script.example/exec'),
    ('nudge_mailer_secret', 's3cret')
    on conflict (name) do update set decrypted_secret = excluded.decrypted_secret;
set role anon;
\echo '--- S12 anon insert with mailer configured (expect INSERT + one POST)'
insert into public.subscribers (email) values ('e@example.com');
reset role;

-- Mailer down: the sign-up must still succeed.
insert into net.fail values (true);
set role anon;
\echo '--- S13 anon insert while mailer errors (expect INSERT, no POST)'
insert into public.subscribers (email) values ('f@example.com');
reset role;

\echo '--- final state (as owner)'
select email, wants_updates, guide_queued_at is not null as queued
  from public.subscribers order by created_at, email;
select url, body from net.calls;

select case when
        (select count(*) from public.subscribers) = 4
    and (select wants_updates from public.subscribers where email = 'b@example.com')
    and (select count(*) from public.subscribers where guide_queued_at is not null) = 1
    and (select guide_queued_at is not null from public.subscribers where email = 'e@example.com')
    and (select count(*) from net.calls) = 1
    and (select body = '{"email": "e@example.com", "secret": "s3cret"}'::jsonb
           and url = 'https://script.example/exec' from net.calls)
    and not has_table_privilege('authenticated', 'public.subscribers', 'INSERT')
    and not has_table_privilege('anon', 'public.subscribers', 'SELECT')
    then 'RESULT: PASS' else 'RESULT: FAIL' end as result;
