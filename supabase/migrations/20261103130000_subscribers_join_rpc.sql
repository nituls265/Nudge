-- Sign-ups go through join_challenge() instead of a direct INSERT, so that
-- signing up again with the "updates" box ticked records the tick.
--
--   * New address  -> inserted (the guide-mailer trigger fires as before).
--   * Known address -> wants_updates can only be switched ON (an unticked
--     repeat never opts anyone out; unsubscribing is a separate step), and
--     nothing changes for someone who has unsubscribed. No second guide
--     email: the AFTER INSERT trigger does not fire for the update path.
--   * Returns nothing, so the response is identical whether or not the
--     address was already on the list.
--
-- The website (anon) may only EXECUTE this function; its direct INSERT
-- grant on the table is removed. Still no SELECT / UPDATE / DELETE.

create or replace function public.join_challenge(p_email text, p_wants_updates boolean default false)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    insert into public.subscribers (email, wants_updates)
    values (btrim(p_email), coalesce(p_wants_updates, false))
    on conflict (lower(email)) do update
        set wants_updates = public.subscribers.wants_updates or excluded.wants_updates
        where public.subscribers.unsubscribed_at is null;
end;
$$;

revoke all on function public.join_challenge(text, boolean) from public, anon, authenticated;
grant execute on function public.join_challenge(text, boolean) to anon;

-- The function (running as the table owner) is now the only way in.
revoke insert on table public.subscribers from anon;
drop policy if exists subscribers_anon_insert on public.subscribers;
