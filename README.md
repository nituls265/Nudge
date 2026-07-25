# Nudge (v0)

An Android app that counts how many times you scroll across all your apps each day, with the goal of nudging you toward more conscious phone use.

A draggable floating bubble shows the live count on top of whatever app you're using, and a Compose dashboard breaks down your history with 7-day, 30-day, and 3-month views plus a peak-activity-hour insight.

> Status: pre-release (v0). Built as a personal project.
>
> No proprietary Google SDKs (Firebase, Play Services, etc.) — F-Droid compatible.
>
> Last documented: 2026-07-24

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
app/src/main/java/com/example/nudgev0/
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
2. Let Gradle sync, then run the `dev` flavor's `app` configuration on a device or emulator running Android 7.0 (API 24) or higher. Use the `friend` flavor (`assembleFriendDebug` / `installFriendDebug`) for a build with all cloud features (telemetry + laptop-sync) disabled at the source.
3. On first launch, tap **Show Bubble** and grant the two permissions when prompted.

## Privacy & telemetry

Nudge's on-device scroll/usage tracking **never leaves your phone by default**
— it lives in a local Room database. Two separate features can send data off
your device, both to the same Supabase project (the developer's, not a
per-user "bring your own backend" — see below):

1. **Opt-in analytics** (retention + product usage) — off by default.
2. **Laptop-sync** (the Chrome extension pairing feature) — not gated by the
   analytics opt-in; it sends data whenever you pair a Sync Code, on any
   `dev`-flavor build. Use a `friend`-flavor build (see
   [Building](#building)) if you don't want this feature or any network
   activity at all — it disables both at the source.

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

If you pair the Chrome extension with the Sync Code shown under **Settings →
Chrome Extension Sync**, your laptop's scroll counts — including which
tracked-site **domains** you scrolled on (e.g. `reddit.com`) — are sent to a
`sync_state` table on the same Supabase project, keyed by that Sync Code
rather than the anonymous analytics ID. There is no separate opt-out toggle
for this in the app; not pairing the extension is the opt-out. A `friend`
flavor build disables it entirely (the Sync Code never resolves).

### How it works / self-hosting

Events are queued locally and sent as a thin HTTPS POST (no SDK, plain
`HttpURLConnection`/`fetch`); they work offline and flush when the network
returns. The destination is configured in
`app/src/main/java/com/example/nudgev0/telemetry/TelemetryConfig.kt` — if you
build from source with those values left blank, nothing is sent. **As shipped
in this repository, those values point at the developer's own Supabase
project** (not a per-user or self-hosted instance) — anyone building from this
exact source and opting in sends data there, same as the shipped APK.

Server schema, the insert-only Row-Level-Security policies, and the retention
/ DAU-WAU-MAU / stickiness queries live in [`supabase/`](supabase/).

## Roadmap / known gaps

- No unit/instrumentation tests beyond the scaffolded `ExampleUnitTest` and `ExampleInstrumentedTest`.
- iOS is on the roadmap; the telemetry core (`telemetry/core/`) is pure Kotlin
  with no platform imports, so it lifts into a KMP `commonMain` unchanged (the
  `telemetry/android/` files become the `androidMain` actuals).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
