# Telemetry verification report

Verification of the Phase-0 retention telemetry (anonymous install ID, opt-in,
`first_open` + `day_active` → Supabase `events`). No new tracking was added.

## Files under verification
- Logic: `app/.../telemetry/core/{TelemetryController,TelemetryEvent,TelemetryPorts}.kt`
- Android impls: `app/.../telemetry/android/AndroidPorts.kt`
- Glue: `telemetry/{Telemetry,TelemetryConfig,TelemetryFlushWorker,TelemetryConsentUi}.kt`,
  wired in `MainActivity.kt`, `MainScreen.kt`, `SettingsSheet.kt`
- SQL: `supabase/{telemetry_schema,retention_queries}.sql`

## Task 1 — Automated tests  ✅ 7/7 pass
`app/src/test/.../telemetry/TelemetryControllerTest.kt` (JVM, in-memory fakes —
no real Supabase). Note: this repo is single-module Android (no `commonMain`), so
the tests live in `app/src/test` against the pure `telemetry.core` logic.
- Install ID is a valid random UUID v4, stable across a simulated restart,
  regenerated after a simulated clear-data/reinstall.
- `day_active` deduped to exactly one per local day; a new day adds exactly one.
- `first_open` fires exactly once, ever (across days + restarts).
- Opt-out deletes the local ID and stops queueing; opt-in mints a NEW id (no
  resurrection of the old one).
- Offline: events queue, then a single flush delivers each exactly once (no dups);
  re-flush on empty queue sends nothing.
- Bonus: delivered payloads contain only the expected fields and no PII tokens.

## Task 2 — Forbidden identifiers  ✅ clean
Matched real API *usage* (not prose). **Telemetry paths: zero** occurrences of
`Settings.Secure.`, `ANDROID_ID`, advertising/AAID, `getDeviceId/getImei`,
`getSerial/Build.SERIAL`, `getMacAddress`. The only `Settings.Secure` in the whole
repo is `AccessibilityServiceUtils.kt` reading `ACCESSIBILITY_ENABLED` (a feature
check, **not** an identifier) — non-telemetry. Runtime payloads were also inspected
(see Task 4 log): only the random `install_id`.

## Task 3 — Retention SQL  ✅ logic correct · off-by-one FIXED
Self-asserting `supabase/validate_retention_test.sql` builds a separate
`events_test` (production `events` untouched), 8 synthetic installs, asserts the
known-good numbers, and drops itself. Could not execute Postgres here (none
installed), but the expected numbers were **independently confirmed twice** — a
Python model and a real `sqlite3` run of the same logic — both matching exactly:

| cohort | size | D1 | D7 | D30 |
|---|---|---|---|---|
| T-40 | 3 | 2 (66.7%) | 1 (33.3%) | 1 (33.3%) |
| T-8  | 2 | 1 (50%)   | 1 (50%)   | 0 |
| T-1  | 1 | 1 (100%)  | 0 | 0 |
| T    | 2 | 0 | 0 | 0 |

DAU=3, WAU=4, MAU=6, stickiness=50%.

**FIXED (off-by-one cohort skew):** the cohort was computed from
`min(timestamp_utc)::date` (**UTC**) while activity uses `event_date` (**device
local**), so for users whose local date ≠ UTC date at first open the D1/D7/D30
were shifted/under-counted. `retention_queries.sql` (and the self-test) now derive
the cohort from the first local activity, sharing the activity calendar:

```sql
cohorts as (
    select install_id, min(event_date) as cohort_date
    from public.events where event_type = 'day_active'
    group by install_id
)
```
Re-validated after the change — the synthetic numbers are unchanged (correct).

**ℹ️ NOTE (convention):** D-N here is *exact-day* retention (active on day N
exactly), not rolling ("active on or after day N"). That's intentional and
documented, but it reads lower than rolling retention — confirm it's the
definition you want for the keep/kill call.

## Task 4 — Debug payload log  ✅
`HttpUrlTransport.postEvents` logs the exact JSON right before send, gated by
`BuildConfig.DEBUG` (enabled `buildConfig=true`). Verified on a debug build:
`D NudgeTelemetry: ▶ events about to send: [{"event_type":"first_open",...}]`.
No-op in release (`BuildConfig.DEBUG` is false; call is strippable).

## Live end-to-end (after wiring real Supabase) — BUG FOUND & FIXED
Wiring the real project surfaced a bug the unit tests + single-row curl missed:
the app sends events as a **batch**, and PostgREST rejects a bulk insert whose
objects have **different keys** (`PGRST102 "All object keys must match"`) — our
`first_open` (has platform/timestamp_utc) and `day_active` (has event_date) had
different shapes. Fixed by emitting a **uniform key set** in every event (null
where N/A). Verified: the app now POSTs successfully (queue drains, HTTP 2xx);
real `first_open` + `day_active` rows land in `events`. Added a debug-gated
HTTP-error log so future POST failures are visible instead of silently swallowed.

## Task 5 — Manual QA checklist (~10 min)
Do this once `TelemetryConfig.kt` has your real Supabase URL + anon key and
`telemetry_schema.sql` has been run.

1. **Schema + validation:** run `supabase/telemetry_schema.sql`, then
   `supabase/validate_retention_test.sql` in the SQL editor → expect
   `✅ ALL RETENTION ASSERTIONS PASSED` and no leftover `events_test`.
2. **Live insert (1 device):** fresh install (or clear data) → opt in on the
   prompt. In the Supabase Table editor, confirm exactly **one** `first_open` and
   **one** `day_active` row for today; `install_id` is a UUID; `platform=android`.
3. **Same-day dedupe:** background/foreground the app a few times → **no** new
   `day_active` rows appear.
4. **Debug log eyeball:** `adb logcat -s NudgeTelemetry` while opting in → confirm
   the JSON matches what landed in Supabase (and contains no surprise fields).
5. **Opt-out:** Settings → Anonymous analytics off. Confirm no new rows on
   subsequent opens, and (debug) the local DataStore no longer holds `install_id`.
6. **Two-device distinct ID:** install on a second device, opt in → confirm a
   **different** `install_id` (no shared/device-derived id).
7. **Offline:** enable airplane mode, opt in / open → no rows yet; restore network,
   reopen → the queued `first_open`/`day_active` appear, with **no duplicates**.
8. **(Optional) release no-op:** a release build shows no `NudgeTelemetry` logs.
