-- Automatic day-30 check-in emails.
--
-- Once a day, pg_cron runs subscribers_send_checkins(): every subscriber who
-- signed up 30+ days ago, hasn't had the check-in, and hasn't unsubscribed is
-- handed to the same Apps Script mailer as the startup guide (with
-- "type": "checkin"), then marked with checkin_sent_at so nobody gets it
-- twice. Uses the Vault secrets from the guide-mailer setup
-- (nudge_mailer_url, nudge_mailer_secret); does nothing until they exist.
--
-- At most 40 per run (the Apps Script also caps check-ins at 40/day), so the
-- daily total with startup guides stays under Gmail's 100/day limit; anyone
-- left over goes out the next day.

-- Supabase ships pg_cron; the guard lets the local/CI test (plain Postgres)
-- apply this file too.
do $$
begin
    if exists (select 1 from pg_available_extensions where name = 'pg_cron') then
        create extension if not exists pg_cron with schema pg_catalog;
    end if;
end
$$;

create or replace function public.subscribers_send_checkins()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    hook_url    text;
    hook_secret text;
    r           record;
    sent        integer := 0;
begin
    select decrypted_secret into hook_url
      from vault.decrypted_secrets where name = 'nudge_mailer_url';
    select decrypted_secret into hook_secret
      from vault.decrypted_secrets where name = 'nudge_mailer_secret';
    if hook_url is null or hook_secret is null then
        return 0;  -- mailer not set up
    end if;

    for r in
        select id, email
          from public.subscribers
         where checkin_sent_at is null
           and unsubscribed_at is null
           and created_at <= now() - interval '30 days'
         order by created_at
         limit 40
         for update skip locked
    loop
        perform net.http_post(
            url     := hook_url,
            body    := jsonb_build_object('secret', hook_secret, 'email', r.email, 'type', 'checkin'),
            headers := '{"Content-Type": "application/json"}'::jsonb
        );
        update public.subscribers set checkin_sent_at = now() where id = r.id;
        sent := sent + 1;
    end loop;
    return sent;
end;
$$;

revoke all on function public.subscribers_send_checkins() from public, anon, authenticated;

-- Daily at 15:17 UTC. cron.schedule() with an existing name replaces that job,
-- so re-running this file is safe. Change the time here if you like.
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.schedule('nudge-day30-checkins', '17 15 * * *',
                              'select public.subscribers_send_checkins();');
    end if;
end
$$;
