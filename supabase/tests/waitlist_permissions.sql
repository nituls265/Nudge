-- Local permission test for the waitlist migration. Run against a THROWAWAY
-- Postgres (never your Supabase project), from the repo root:
--   psql -d <scratch_db> -X -q -f supabase/tests/waitlist_permissions.sql
-- Every T2..T8 step must print an ERROR; T1 must succeed; one row at the end.
\set ON_ERROR_STOP 0
create role anon nologin; create role authenticated nologin;
grant usage on schema public to anon, authenticated;
-- Supabase gives API roles broad default privileges on new tables; emulate that
-- so the migration's REVOKE is actually exercised.
alter default privileges in schema public grant all on tables to anon, authenticated;
\i supabase/migrations/20261102120000_waitlist.sql
\echo '--- apply twice (idempotent)'
\i supabase/migrations/20261102120000_waitlist.sql
set role anon;
\echo '--- T1 anon insert (expect INSERT 0 1)'
insert into public.waitlist (email) values ('a@example.com');
\echo '--- T2 duplicate, different case (expect unique violation)'
insert into public.waitlist (email) values ('A@Example.com');
\echo '--- T3 invalid email (expect check violation)'
insert into public.waitlist (email) values ('not-an-email');
\echo '--- T4 anon select (expect permission denied)'
select * from public.waitlist;
\echo '--- T5 anon update/delete (expect permission denied)'
update public.waitlist set email='x@y.zz'; delete from public.waitlist;
\echo '--- T6 anon set created_at / id (expect permission denied)'
insert into public.waitlist (email, created_at) values ('b@example.com', now());
\echo '--- T7 insert ... returning (expect permission denied -> site must use return=minimal)'
insert into public.waitlist (email) values ('c@example.com') returning id;
reset role;
set role authenticated;
\echo '--- T8 authenticated insert (expect permission denied)'
insert into public.waitlist (email) values ('d@example.com');
reset role;
\echo '--- final rows (as owner)'
select email from public.waitlist order by created_at;
