-- Automatic startup-guide email for new 30-day-challenge sign-ups.
--
-- On each new row in public.subscribers, Postgres (via pg_net) POSTs the
-- address to a Google Apps Script web app that sends the guide from the
-- Nudge Gmail account (email/apps-script/Code.gs). The web app's URL and a
-- shared secret live in Supabase Vault, never in this repository:
--
--   select vault.create_secret('<web app URL>', 'nudge_mailer_url');
--   select vault.create_secret('<random secret>', 'nudge_mailer_secret');
--
-- Until both secrets exist, the trigger does nothing, so sign-ups still work.
-- A duplicate sign-up fails its INSERT, so no address gets the guide twice.
-- Mailer problems never block a sign-up: errors are swallowed, and pg_net
-- sends the request after the transaction commits.

-- pg_net ships with Supabase; the guard lets the local/CI test (plain
-- Postgres with stand-in `net` and `vault` schemas) apply this file too.
do $$
begin
    if exists (select 1 from pg_available_extensions where name = 'pg_net') then
        create extension if not exists pg_net with schema extensions;
    end if;
end
$$;

create or replace function public.subscribers_send_guide()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    hook_url    text;
    hook_secret text;
begin
    select decrypted_secret into hook_url
      from vault.decrypted_secrets where name = 'nudge_mailer_url';
    select decrypted_secret into hook_secret
      from vault.decrypted_secrets where name = 'nudge_mailer_secret';

    if hook_url is null or hook_secret is null then
        return null;  -- mailer not set up yet
    end if;

    perform net.http_post(
        url     := hook_url,
        body    := jsonb_build_object('secret', hook_secret, 'email', new.email),
        headers := '{"Content-Type": "application/json"}'::jsonb
    );

    update public.subscribers set guide_queued_at = now() where id = new.id;
    return null;
exception when others then
    return null;  -- never let the mailer break a sign-up
end;
$$;

revoke all on function public.subscribers_send_guide() from public, anon, authenticated;

drop trigger if exists subscribers_send_guide on public.subscribers;
create trigger subscribers_send_guide
    after insert on public.subscribers
    for each row execute function public.subscribers_send_guide();
