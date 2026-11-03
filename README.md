# Nudge (v0)

An Android app that counts how many times you scroll across all your apps each day, with the goal of nudging you toward more conscious phone use.

A draggable floating bubble shows the live count on top of whatever app you're using, and a Compose dashboard breaks down your history with 7-day, 30-day, and 3-month views plus a peak-activity-hour insight.

> Status: pre-release (v0). Built as a personal project.
>
> No proprietary Google SDKs (Firebase, Play Services, etc.) — F-Droid compatible.
> Accepted into F-Droid ([MR !44067](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44067)
> merged); the first F-Droid build is still being published. Latest release:
> [v1.12](https://github.com/nituls265/Nudge/releases/tag/v1.12).
>
> [<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="45">](https://f-droid.org/packages/com.nitulshah.nudge/)
>
> Last documented: 2026-11-01

## Features

- **All-app scroll counter.** Uses an Android `AccessibilityService` to detect scroll gestures across the whole device, not just inside one app.
- **Floating bubble overlay.** Shows the running daily count on top of any app. Drag it anywhere; it snaps to the nearest screen edge with a smooth animation and pulses on each new scroll. Position is remembered across service restarts.
- **History dashboard** (Jetpack Compose + Material 3): today's total, peak hour, average per day, and a bar chart with 7 / 30 / 90 day toggles. The 3-month view is bucketed into weekly averages.
- **Pause and reset.** Pause the counter when you don't want it tracking, or reset today's count manually. A manual reset saves the current count to history before wiping.
- **Crash-safe persistence.** Every counted scroll is written immediately to `SharedPreferences` and throttled (≤ once per 2 s) to a Room database, so the count survives the OS killing the service.
- **Midnight rollover.** Yesterday's tally is locked into the database and the live counter resets at midnight via a `WorkManager` `PeriodicWorkRequest` scheduled from `MainActivity`.
- **Anonymous analytics (opt-in).** Daily totals and feature usage are optionally sent to a Supabase project — see [Privacy & telemetry](#privacy--telemetry).

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Android `AccessibilityService` + `WindowManager` overlay
- Room (SQLite) for history, SharedPreferences for the live counter and bubble position
- WorkManager for the daily reset job
- Supabase (Postgres + PostgREST) for opt-in telemetry and laptop-sync — plain HTTPS calls, no SDK
- Gradle Kotlin DSL, kapt, version catalog (`gradle/libs.versions.toml`)
- R8 code and resource shrinking enabled for release builds (keep rules in `app/proguard-rules.pro`)
- minSdk 24, targetSdk 36, Java 11

## How it works

The app revolves around a single source of truth in `MyAccessibilityService`: the live count, scroll timestamps, and pause/bubble flags are exposed as `MutableStateFlow`s on the service's companion object. Both the UI and the bubble read from those flows, so everything stays in sync without explicit IPC.

```
AccessibilityEvent
        │
        ▼
MyAccessibilityService  ── StateFlow ──▶  ScrollViewModel ──▶  MainScreen (Compose)
        │                                       │
        ├── SharedPreferences (live count)      └── Room history (chart data)
        │
        └── Floating bubble (WindowManager overlay)

Midnight:  ResetWorker ──▶ Room (lock yesterday) + Supabase + wipe prefs
```

### Scroll detection (filtering noise out)

`onAccessibilityEvent` listens for `TYPE_VIEW_SCROLLED` events and runs them through several filters so that typing, animations, and inertial fling all count as a single swipe:

- **A — Keyboard skip.** Ignore events from packages whose name contains `inputmethod`, `keyboard`, or `gboard`.
- **B — Window-state shield.** Ignore scrolls within 500 ms of a window-state change (keyboard opening/closing, screen transitions).
- **C — Typing shield.** Ignore scrolls within 500 ms of a text-change event.
- **D — `EditText` skip.** Ignore scrolls originating inside an `EditText`.
- **E — Item-count cascade.** If the adapter's `itemCount` changed since the last event, treat it as programmatic and skip.
- **F — Cascade debounce.** Ignore scrolls within 500 ms of the last programmatic cascade.
- **G — Continuous-motion grouper.** Within 400 ms of the previous scroll event, treat the current one as the kinetic glide of the same swipe and skip — only the first event in a fling burst counts.

Anything that survives all seven filters increments the counter, gets written to `SharedPreferences` immediately, and is debounced into Room every 2 s.

## Project structure

```
app/src/main/java/com/nitulshah/nudge/
├── MainActivity.kt              # Compose entry point, builds the ViewModel factory, schedules ResetWorker
├── MainScreen.kt                # Dashboard UI (cards, chart, controls, time chips)
├── ScrollViewModel.kt           # Bridges service flows + Room into chart data
├── ViewModelFactory.kt          # Injects the ScrollDao into ScrollViewModel
├── MyAccessibilityService.kt    # Scroll detection (7 filters), overlay bubble, SSOT state
├── AccessibilityServiceUtils.kt # "Is the service enabled?" helper
├── ResetWorker.kt               # Midnight rollover (Room write + analytics + wipe)
├── AnalyticsHelper.kt           # Product-analytics events (opt-in, Supabase)
├── data/ScrollDatabase.kt       # Room DB, ScrollDay entity, ScrollDao
└── ui/theme/                    # Compose theme (Color, Theme, Type)

app/src/main/res/
├── layout/bubble_layout.xml         # Floating bubble view
├── layout/scroll_counter_layout.xml
├── xml/accessibility_service_config.xml
├── xml/backup_rules.xml
└── xml/data_extraction_rules.xml
```

## Permissions

The app needs two permissions, both granted by the user from system settings (not from a runtime dialog):

- **`SYSTEM_ALERT_WINDOW`** — to draw the floating bubble over other apps. Declared in `AndroidManifest.xml`; the user grants it via Settings → Apps → Display over other apps.
- **Accessibility service** (`BIND_ACCESSIBILITY_SERVICE`) — to read `TYPE_VIEW_SCROLLED` events from any app. The user must enable "Nudge Scroll Tracker" under Settings → Accessibility.

The "Show Bubble" button checks both and routes the user to the right settings screen if either is missing.

## Product-analytics events

Opt-in only (see [Privacy & telemetry](#privacy--telemetry)) — sent to a
`product_events` table on the same Supabase project as the retention
telemetry, keyed by the same anonymous install ID.

| Event | When it fires | Params |
|---|---|---|
| `daily_scroll_summary` | Midnight, from `ResetWorker` | `total_scrolls: Int` |
| `feature_bubble_toggled` | User taps Show / Hide Bubble | `is_visible: Bool` |
| `action_pause_toggled` | User taps Pause / Resume | `is_paused: Bool` |
| `action_manual_reset` | User taps "Reset Today's Data" | none |
| `scroll_session_snapshot` | App backgrounded (`onStop`), if today's count > 0 | `total_scrolls: Int` |
| `intervention_triggered` | An intervention overlay fires | `level: Int` |
| `intervention_response` | User responds to an intervention | `response: "break" \| "ignore"`, `level: Int` |

## Building

Requirements:

- Android Studio (latest stable)
- JDK 11
- Android SDK 36

Steps:

1. Clone the repo and open it in Android Studio.
2. Let Gradle sync, then run the `dev` flavor's `app` configuration on a device or emulator running Android 7.0 (API 24) or higher. Use the `foss` flavor (`assembleFossDebug` / `installFossDebug`) for a build with analytics/telemetry compiled out entirely — laptop-sync still works, feature parity with `dev` otherwise.
3. On first launch, tap **Show Bubble** and grant the two permissions when prompted.

## Privacy & telemetry

Nudge's on-device scroll/usage tracking **never leaves your phone by default**
— it lives in a local Room database. Two separate features can send data off
your device, both to the same Supabase project (the developer's, not a
per-user "bring your own backend" — see below):

1. **Opt-in analytics** (retention + product usage) — off by default, and
   compiled out entirely (not just toggled off) in `foss`-flavor builds,
   including the one published to F-Droid.
2. **Laptop-sync** (the Chrome extension pairing feature) — off by default
   and separate from the analytics opt-in; it is available on both `dev` and
   `foss` builds. Nothing is generated, polled or sent until you switch it on
   under **Settings → Chrome Extension Sync**, and switching it off deletes the
   Sync Code (see [Laptop-sync](#laptop-sync-chrome-extension) below).

### Opt-in analytics

A one-time prompt on first launch lets you turn this on, and you can turn it
off any time under **Settings → Anonymous analytics**. Two kinds of events are
sent, both keyed to the same anonymous ID:

- **Retention** (`events` table) — `first_open` (once, ever) and `day_active`
  (at most once per local calendar day): anonymous ID, timestamp/date, app
  version, platform.
- **Product usage** (`product_events` table, listed in full in
  [Product-analytics events](#product-analytics-events)) — aggregate daily/
  session **scroll counts** (an integer total, not individual scroll events or
  content), feature-toggle states (bubble/pause), and intervention
  level/response.

**Still never sent or read, by either stream:** scroll *content*, per-app
breakdown, unlock/session timing, accounts, location, IP-derived identity, or
any device/advertising identifier (no `ANDROID_ID`, advertising ID, IMEI, MAC,
or serial). No Google Analytics, no third-party tracker.

**How to opt out / delete:** toggle **Settings → Anonymous analytics** off.
This stops both the retention and product-usage sends **and deletes the local
anonymous ID** (a new one is only created if you opt in again). It does
**not** affect laptop-sync — that's a separate toggle-less feature, see below.
Uninstalling or clearing app data also removes the ID.

### Laptop-sync (Chrome extension)

Laptop-sync is off by default. If you switch it on under **Settings → Chrome
Extension Sync** and pair the Chrome extension with the Sync Code shown there, your laptop's scroll counts — including which
tracked-site **domains** you scrolled on (e.g. `reddit.com`) — are sent to a
`sync_state` table on the same Supabase project, keyed by that Sync Code
rather than the anonymous analytics ID. While it is on, the app also polls that
table every few seconds. Switching it off in the same place stops all of that
and deletes the Sync Code. This works the same way on both `dev` and `foss`
builds.

### How it works / self-hosting

Events are queued locally and sent as a thin HTTPS POST (no SDK, plain
`HttpURLConnection`/`fetch`); they work offline and flush when the network
returns. The destination is configured in
`app/src/main/java/com/nitulshah/nudge/telemetry/TelemetryConfig.kt` — if you
build from source with those values left blank, nothing is sent. **As shipped
in this repository, those values point at the developer's own Supabase
project** (not a per-user or self-hosted instance) — anyone building from this
exact source and opting in sends data there, same as the shipped APK.

Server schema, the insert-only Row-Level-Security policies, and the retention
/ DAU-WAU-MAU / stickiness queries live in [`supabase/`](supabase/).

## Landing page (GitHub Pages)

A static, no-framework landing page lives in [`docs/`](docs/) and is served by
GitHub Pages at **https://nituls265.github.io/Nudge/**. It costs nothing,
loads no fonts, trackers or third-party scripts, and works with JavaScript off
(download link and content). Its only outside request is the iPhone waitlist
insert to Supabase, made only when someone submits the form.

| Path | What it is |
|---|---|
| `docs/config.js` | **The one config block** (repo, download URL, F-Droid URL, `SHOW_COUNTERS`, Supabase publishable key, contact email) |
| `docs/index.html`, `style.css`, `app.js` | The page (plain HTML/CSS/JS) |
| `docs/qr.svg` | QR code for the download URL, generated locally at build time |
| `docs/stats.json` | GitHub download count, written by the stats workflow |
| `docs/assets/` | App screenshots (`shot-*.webp`), logo; drop the demo recording here |
| `scripts/build_site.py` | Regenerates `qr.svg` and syncs config values into the HTML |
| `scripts/update_stats.py` | Sums release download counts → `docs/stats.json` |
| `supabase/migrations/20261102120000_waitlist.sql` | `waitlist` table: anon may INSERT an email, nothing else |

### Enable GitHub Pages (one-time)

1. On GitHub, open the repo → **Settings** → **Pages** (left sidebar, under
   "Code and automation").
2. Under **Build and deployment** → **Source**, choose **Deploy from a branch**.
3. Under **Branch**, pick **`main`**, then the folder **`/docs`**, and click **Save**.
4. Wait about a minute, then reload the Pages settings screen. It shows
   "Your site is live at https://nituls265.github.io/Nudge/". Each push to
   `main` that touches `docs/` redeploys automatically.
5. Optional: tick **Enforce HTTPS** (on by default for `github.io`). For a custom
   domain, enter it under **Custom domain** and add the DNS records GitHub shows.

### Apply the waitlist migration (one-time)

In the Supabase dashboard → **SQL Editor**, paste and run
`supabase/migrations/20261102120000_waitlist.sql` (or `supabase db push` with
the CLI). It creates only the new `waitlist` table and doesn't touch `events`,
`product_events` or `sync_state`. Read sign-ups in **Table Editor → waitlist**.
The website has no read access to any table. `supabase/tests/waitlist_permissions.sql`
checks this against a throwaway Postgres, and the **Site checks** workflow runs it in CI.

### 30-day challenge emails

After someone taps **Download for Android**, the page shows a setup guide and
an optional form: email for a one-time day-30 check-in, plus an unticked box
for "updates and articles, a few times a year". Sign-ups go to the
`subscribers` table (insert-only from the website, separate from the waitlist
and from all app data).

**1. Create the table (one-time).** Supabase → **SQL Editor** → **+ New query**
→ paste `supabase/migrations/20261103120000_subscribers.sql` → **Run**.

**2. Weekly routine (about 5 minutes).** In the SQL Editor, run the queries in
`supabase/queries/subscribers.sql`:
1. Query 1 lists who is due for the day-30 check-in.
2. Send them `email/day30-checkin.txt` from the Nudge Gmail with their
   addresses in **BCC**. Replies come back to the same inbox.
3. Query 2 marks them as sent.

Someone replies STOP → query 3. Someone asks to be deleted → query 4.
Updates mailing (a few times a year) → query 5.

**3. Automatic startup-guide email (one-time, about 15 minutes, free).**
Supabase pings a Google Apps Script running in the Nudge Gmail account, which
emails the guide from that account. The script's URL and a shared secret live
in Supabase Vault; nothing secret is committed.

1. Create the Nudge Gmail account and sign in to it.
2. Supabase → SQL Editor → **+ New query** → paste
   `supabase/migrations/20261103120100_subscribers_guide_mailer.sql` → **Run**.
3. Make up a long random secret (e.g. 30+ random characters from a password
   manager) and keep it handy for steps 5 and 8.
4. While signed in to the Nudge Gmail, open https://script.google.com →
   **New project**. Name it "Nudge mailer". Replace the editor contents with
   `email/apps-script/Code.gs` and click **Save**.
5. **Project Settings** (gear icon) → **Script Properties** → **Add script
   property**: name `MAILER_SECRET`, value = your secret → **Save**.
6. Back in **Editor**, choose `sendTestToMyself` in the function dropdown →
   **Run** → allow the Gmail permission (pick the Nudge account; if Google
   says the app isn't verified, click **Advanced → Go to Nudge mailer**).
   Check the Nudge inbox for the test email.
7. **Deploy → New deployment** → gear next to "Select type" → **Web app**.
   Execute as: **Me**. Who has access: **Anyone**. **Deploy**, then copy the
   **Web app URL** (ends in `/exec`).
8. Supabase → SQL Editor → **+ New query**, fill in both values, **Run**:
   ```sql
   select vault.create_secret('PASTE_WEB_APP_URL', 'nudge_mailer_url');
   select vault.create_secret('PASTE_YOUR_SECRET', 'nudge_mailer_secret');
   ```
9. Test: sign up on the site with an address you can check (not the Nudge
   Gmail). The guide should arrive within a minute, and **Table Editor →
   subscribers** shows a time in `guide_queued_at`.
10. In `docs/config.js` set `GUIDE_EMAIL_ENABLED: true` (the form then
    promises the guide by email), run `python3 scripts/build_site.py`, commit.

Changed `Code.gs` later? **Deploy → Manage deployments → ✏️ → Version: New
version → Deploy** keeps the same URL. The script sends at most 60 guides a
day (Gmail allows 100), so bulk fake sign-ups can't exhaust the account.
`supabase/tests/subscribers_permissions.sql` checks the table permissions and
the trigger (with stand-ins for pg_net and Vault) in CI.

### Stats workflow

`.github/workflows/stats.yml` runs daily, after every Release run, and on
demand (**Actions → Site stats → Run workflow**). It sums `download_count`
across all release assets using only the built-in `GITHUB_TOKEN` and commits
`docs/stats.json` only when the number changes. It also records whether the
latest release has `app-foss-release.apk`. If not, the page links to the
releases page instead of a 404. The page shows "Downloads on GitHub: N" only
when `SHOW_COUNTERS: true` in `docs/config.js`. That number counts GitHub downloads, not
users: F-Droid publishes no download counts, so the page never claims a total.

### Editing the page

1. Edit `docs/config.js` (e.g. set `FDROID_URL` once F-Droid is live, or flip
   `SHOW_COUNTERS`).
2. Run `pip install -r scripts/requirements.txt && python3 scripts/build_site.py`
   and commit the result. CI (`Site checks`) fails if you forget.
3. To add the demo recording: put `demo.mp4` (and optionally `demo.webm`
   and `demo-poster.webp`) in `docs/assets/`, then in `docs/index.html` swap the
   demo `<img>` for the commented-out `<video>` block next to it. Keep it small:
   9:16, muted, a 5–8 s loop, ideally < 1 MB.

**Release constraint:** every GitHub release must attach the APK as exactly
`app-foss-release.apk` (no version in the name). The download button and QR
code use `/releases/latest/download/app-foss-release.apk`. `release.yml`
already does this, so don't rename the asset.

**Analytics:** the site has none. If page analytics are ever wanted, use
GoatCounter only (cookie-free, no personal data). It is not built in, and adding
it requires allowing its domain in the page's Content-Security-Policy.

## Roadmap / known gaps

- No unit/instrumentation tests beyond the scaffolded `ExampleUnitTest` and `ExampleInstrumentedTest`.
- iOS is on the roadmap; the telemetry core (`telemetry/core/`) is pure Kotlin
  with no platform imports, so it lifts into a KMP `commonMain` unchanged (the
  `telemetry/android/` files become the `androidMain` actuals).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
