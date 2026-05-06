# Nudge (v0)

An Android app that counts how many times you scroll across all your apps each day, with the goal of nudging you toward more conscious phone use.

A draggable floating bubble shows the live count on top of whatever app you're using, and a Compose dashboard breaks down your history with 7-day, 30-day, and 3-month views plus a peak-activity-hour insight.

> Status: pre-release (v0). Built as a personal project — package id is still `com.example.nudgev0`.

## Features

- **All-app scroll counter.** Uses an Android `AccessibilityService` to detect scroll gestures across the whole device, not just inside one app.
- **Floating bubble overlay.** Shows the running daily count on top of any app. Drag it anywhere; it snaps to the nearest screen edge with a smooth animation and pulses on each new scroll.
- **History dashboard** (Jetpack Compose + Material 3): today's total, peak hour, average per day, and a bar chart with 7 / 30 / 90 day toggles. The 3-month view is bucketed into weekly averages.
- **Pause and reset.** Pause the counter when you don't want it tracking, or reset today's count manually.
- **Crash-safe persistence.** Every counted scroll is written immediately to `SharedPreferences` and throttled (≤ once per 2 s) to a Room database, so the count survives the OS killing the service.
- **Midnight rollover.** Yesterday's tally is locked into the database and the live counter resets at midnight.
- **Anonymous analytics.** Daily totals and feature usage are sent to Firebase Analytics.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Android `AccessibilityService` + `WindowManager` overlay
- Room (SQLite) for history, SharedPreferences for the live counter
- WorkManager for the daily reset job
- Firebase Analytics
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

Midnight:  ResetWorker ──▶ Room (lock yesterday) + Firebase + wipe prefs
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
├── MainActivity.kt              # Compose entry point, builds the ViewModel factory
├── MainScreen.kt                # Dashboard UI (cards, chart, controls, time chips)
├── ScrollViewModel.kt           # Bridges service flows + Room into chart data
├── ViewModelFactory.kt          # Injects the ScrollDao into ScrollViewModel
├── MyAccessibilityService.kt    # Scroll detection, overlay bubble, SSOT state
├── AccessibilityServiceUtils.kt # "Is the service enabled?" helper
├── ResetWorker.kt               # Midnight rollover (Room write + analytics + wipe)
├── AnalyticsHelper.kt           # Firebase Analytics events
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

## Firebase Analytics events

| Event | When it fires | Params |
|---|---|---|
| `daily_scroll_summary` | Midnight, from `ResetWorker` | `total_scrolls: Int` |
| `feature_bubble_toggled` | User taps Show / Hide Bubble | `is_visible: Bool` |
| `action_pause_toggled` | User taps Pause / Resume | `is_paused: Bool` |
| `action_manual_reset` | User taps "Reset Today's Data" | none |

## Building

Requirements:

- Android Studio (latest stable)
- JDK 11
- Android SDK 36

Steps:

1. Clone the repo and open it in Android Studio.
2. Add your own `app/google-services.json` from the Firebase console (the file is gitignored).
3. Let Gradle sync, then run the `app` configuration on a device or emulator running Android 7.0 (API 24) or higher.
4. On first launch, tap **Show Bubble** and grant the two permissions when prompted.

## Roadmap / known gaps

- `ResetWorker` is implemented but isn't enqueued on app start yet — the midnight rollover currently only works if the accessibility service happens to be alive across the day boundary. Wiring it up in `MainActivity` (a daily `PeriodicWorkRequest`) is the next obvious step.
- Package id is still `com.example.nudgev0` — needs renaming before any Play Store release.
- No unit/instrumentation tests beyond the scaffolded `ExampleUnitTest` and `ExampleInstrumentedTest`.
- Bubble position isn't persisted across service restarts.

## License

Not yet specified.
