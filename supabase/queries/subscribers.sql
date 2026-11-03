-- Saved queries for the 30-day challenge list. Run each one on its own in the
-- Supabase SQL Editor (select the lines, then Run). Never put these in client
-- code: the website can only insert, by design.

-- 1. Who is due for the day-30 check-in? The daily job (15:17 UTC) emails
--    these automatically. Only if the job is off: send email/day30-checkin.txt
--    by hand with these in BCC, then run query 2.
select email, created_at
  from public.subscribers
 where checkin_sent_at is null
   and unsubscribed_at is null
   and created_at <= now() - interval '30 days'
 order by created_at;

-- 2. Only after sending check-ins BY HAND: mark those same people as done.
--    (Same filter as query 1. The automatic job marks people itself.)
update public.subscribers
   set checkin_sent_at = now()
 where checkin_sent_at is null
   and unsubscribed_at is null
   and created_at <= now() - interval '30 days';

-- 3. Someone clicked Unsubscribe (or asked by email): no more emails of any kind.
--    Replace the address, then run.
update public.subscribers
   set unsubscribed_at = now(), wants_updates = false
 where lower(email) = lower('person@example.com');

-- 4. Someone asked to be deleted entirely (GDPR / "forget me").
delete from public.subscribers
 where lower(email) = lower('person@example.com');

-- 5. Before an updates mailing: who wants updates and articles?
select email
  from public.subscribers
 where wants_updates
   and unsubscribed_at is null
 order by created_at;

-- 6. Quick numbers.
select count(*)                                            as total_signups,
       count(*) filter (where wants_updates)               as want_updates,
       count(*) filter (where guide_queued_at is not null) as guide_emailed,
       count(*) filter (where checkin_sent_at is not null) as checkin_sent,
       count(*) filter (where unsubscribed_at is not null) as unsubscribed
  from public.subscribers;
