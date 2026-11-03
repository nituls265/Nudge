-- Local permission + behaviour test for the 30-day-challenge sign-ups.
-- Run against a THROWAWAY Postgres (never your Supabase project), repo root:
--   createdb nudge_subs_test
--   psql -d nudge_subs_test -X -q -f supabase/tests/subscribers_permissions.sql
-- Expect exactly 9 "ERROR:" lines (the steps marked "expect error") and a
-- final "RESULT: PASS". `net` and `vault` stand in for pg_net and Vault.
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
\i supabase/migrations/20261103130000_subscribers_join_rpc.sql
\echo '--- apply all twice (idempotent)'
\i supabase/migrations/20261103120000_subscribers.sql
\i supabase/migrations/20261103120100_subscribers_guide_mailer.sql
\i supabase/migrations/20261103130000_subscribers_join_rpc.sql

set role anon;
\echo '--- S1 join, box unticked, mailer not configured (expect ok, no mail)'
select public.join_challenge('a@example.com', false);
\echo '--- S2 same person again (other case, padded), box ticked (expect ok; updates switched on)'
select public.join_challenge('  A@Example.COM ', true);
\echo '--- S3 same person again, box unticked (expect ok; updates stay on)'
select public.join_challenge('a@example.com', false);
\echo '--- S4 invalid email (expect error)'
select public.join_challenge('nope', true);
\echo '--- S5 null email (expect error)'
select public.join_challenge(null, false);
\echo '--- S6 direct insert, bypassing the function (expect error)'
insert into public.subscribers (email) values ('b@example.com');
\echo '--- S7 select (expect error)'
select * from public.subscribers;
\echo '--- S8 update (expect error)'
update public.subscribers set wants_updates = true;
\echo '--- S9 delete (expect error)'
delete from public.subscribers;
\echo '--- S10 call the trigger function directly (expect error)'
select public.subscribers_send_guide();
reset role;

set role authenticated;
\echo '--- S11 authenticated calls join (expect error)'
select public.join_challenge('c@example.com', false);
\echo '--- S12 authenticated direct insert (expect error)'
insert into public.subscribers (email) values ('c@example.com');
reset role;

-- An unsubscribed person signing up again with the box ticked stays opted out.
insert into public.subscribers (email, unsubscribed_at) values ('gone@example.com', now());
set role anon;
\echo '--- S13 unsubscribed person re-joins with box ticked (expect ok; nothing changes)'
select public.join_challenge('gone@example.com', true);
reset role;

-- Configure the mailer: a new sign-up POSTs once; a repeat does not.
insert into vault.decrypted_secrets values
    ('nudge_mailer_url', 'https://script.example/exec'),
    ('nudge_mailer_secret', 's3cret')
    on conflict (name) do update set decrypted_secret = excluded.decrypted_secret;
set role anon;
\echo '--- S14 new person with mailer configured (expect ok + one POST)'
select public.join_challenge('e@example.com', false);
\echo '--- S15 same person again, box ticked (expect ok, no second POST)'
select public.join_challenge('e@example.com', true);
reset role;

-- Mailer down: the sign-up must still succeed.
insert into net.fail values (true);
set role anon;
\echo '--- S16 new person while mailer errors (expect ok, no POST)'
select public.join_challenge('f@example.com', false);
reset role;

\echo '--- final state (as owner)'
select email, wants_updates, guide_queued_at is not null as queued, unsubscribed_at is not null as unsub
  from public.subscribers order by created_at, email;
select url, body from net.calls;

select case when
        (select count(*) from public.subscribers) = 4
    and (select wants_updates from public.subscribers where email = 'a@example.com')
    and not (select wants_updates from public.subscribers where email = 'gone@example.com')
    and (select wants_updates from public.subscribers where email = 'e@example.com')
    and (select count(*) from public.subscribers where guide_queued_at is not null) = 1
    and (select guide_queued_at is not null from public.subscribers where email = 'e@example.com')
    and (select count(*) from public.subscribers where email = 'f@example.com') = 1
    and (select count(*) from net.calls) = 1
    and (select body = '{"email": "e@example.com", "secret": "s3cret"}'::jsonb
           and url = 'https://script.example/exec' from net.calls)
    and not has_table_privilege('anon', 'public.subscribers', 'INSERT')
    and not has_column_privilege('anon', 'public.subscribers', 'email', 'INSERT')
    and not has_table_privilege('anon', 'public.subscribers', 'SELECT')
    and has_function_privilege('anon', 'public.join_challenge(text, boolean)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.join_challenge(text, boolean)', 'EXECUTE')
    then 'RESULT: PASS' else 'RESULT: FAIL' end as result;
