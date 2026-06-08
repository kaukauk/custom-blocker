# Custom Blocker — Comprehensive Implementation Plan

A ground-up Android app-blocker modeled on the feature set of established blockers, built in Kotlin +
Jetpack Compose, offline-first. This plan is informed by the observable behavior of
existing apps in this space and applies modern Android best practices where their
older (targetSdk 33, 2023-era) approaches can be improved.

---

## 1. Vision & Scope

A profile/schedule-based blocker. Each **Profile** blocks a set of **apps + websites + keywords**
when **all** its **conditions** are currently satisfied (logical AND). Conditions: Time,
Location (geofence, invertible), Wi-Fi (SSID), Usage Limit (min/day or /hour), Launch Count
(/day or /hour), Charger. Per-profile toggles: block launch, block notifications, block
unsupported browsers, auto-block newly installed apps, allow-list vs block-list mode.
Plus **Strict Mode** (anti-circumvention), **Statistics**, and a clean onboarding/permissions flow.

**Non-goals (for now):** accounts, cloud sync, payments, ads, the "Academy" content — all
present in some blockers but explicitly out of scope. Offline only.

---

## 2. Android Platform Realities (the honest constraints)

The whole thing is legally achievable by a normal sideloaded app with no root. But several
pieces are inherently fragile or OEM-specific — naming them up front so the plan is honest:

- **Accessibility is the linchpin.** Detecting the foreground app/URL and ejecting the user
  both rely on an `AccessibilityService`. If the user disables it in Settings, blocking stops.
  Strict Mode tries to defend it, but this is a cat-and-mouse with the OS.
- **"Block device Settings" is OEM-specific and brittle.** this typically needs a hardcoded
  whitelist of Settings sub-activities per manufacturer (Samsung/OnePlus/MIUI/AOSP). It will
  never be perfectly portable. We target AOSP/Pixel first.
- **Battery optimization kills background work.** Geofencing, alarms, and the watchdog all
  suffer if the OEM aggressively dozes the app. Onboarding must request battery-optimization
  exemption.
- **Modern API tightening** vs the older targetSdk 33 norm: API 34+ requires a declared
  `foregroundServiceType` (+matching permission); exact alarms need `SCHEDULE_EXACT_ALARM`;
  background location is a separate, gated runtime grant. We design for these from day one.
- **Uninstall blocking** works because Android refuses to uninstall an app that is an active
  **Device Admin**. Strict Mode gates *deactivating* that admin behind the unlock condition.

---

## 3. Tech Stack & Rationale

| Concern | Choice | Why |
|---|---|---|
| Language | **Kotlin** | Standard; coroutines/Flow are ideal for a reactive engine |
| UI | **Jetpack Compose + Material 3** | Chosen; less boilerplate, modern |
| DI | **Hilt** | Clean injection into services, receivers, workers, VMs |
| Persistence | **Room** | Relational profile/condition model maps perfectly |
| Settings/prefs | **DataStore (Preferences)** | Async, Flow-based app settings |
| Async/reactivity | **Coroutines + Flow** | DB changes stream straight into the engine |
| Deferred/periodic work | **WorkManager** | Counter resets, re-arm, watchdog (modern replacement for older deprecated job schedulers) |
| Exact timing | **AlarmManager** (`setExactAndAllowWhileIdle`) | Profile schedule boundaries |
| Geofencing | **Play Services Location** (`GeofencingClient`) | Battery-efficient enter/exit/dwell |
| Navigation | **Navigation-Compose** | Single-activity UI |
| Charts (stats) | **Vico** (or Compose canvas) | Usage graphs |

**Single Gradle module** to start (`:app`), with strict internal package boundaries (below).
Split into `:core`, `:data`, `:engine`, `:feature-*` modules later only if build times demand it.

---

## 4. High-Level Architecture

Two enforcement pillars (both root-free):

1. **`BlockerAccessibilityService`** — receives `TYPE_WINDOW_STATE_CHANGED` /
   `TYPE_WINDOW_CONTENT_CHANGED` events. Gives us (a) the **current foreground package**
   instantly and event-driven (no polling), and (b) the **browser URL** by reading the URL-bar
   node. Also performs the **eject** via a HOME intent.
2. **`OverlayController`** — draws a full-screen `TYPE_APPLICATION_OVERLAY` block screen.

> **Improvement over the common approach:** some blockers poll `UsageStatsManager` on a short
> interval in a separate process *and* use accessibility. We make the **AccessibilityService the single
> primary detector** (event-driven → instant, battery-cheap) and keep a **`UsageStatsManager`
> sampler only as a fallback** for the rare devices/cases where accessibility events are sparse.

### Process model decision
Running accessibility in a separate `:AccessibilityService` process for kill-resilience
forces cross-process Room invalidation (`MultiInstanceInvalidationService`) and IPC.
**We start single-process** (one Room instance, one engine, drastically simpler). Separate-process
hardening is a documented later option, not a v1 requirement.

### Data flow
```
DB (Room)  ──Flow──►  BlockingEngine (in-memory active-rule snapshot)
                           ▲                         │ decision
 condition signals ────────┘                         ▼
 (geofence/wifi/charger receivers, alarms,    BlockerAccessibilityService
  usage/launch counters)                        ├─ foreground pkg / URL  (input)
                                                └─ eject + OverlayController.show/hide (output)
```
The **engine** holds a cached snapshot of enabled profiles and live condition bits; it never
touches the DB on the hot path. Receivers/alarms/counters update condition state; DB edits
re-publish the snapshot via Flow.

---

## 5. Project Structure (packages under `com.customblocker`)

```
app/            Application, Hilt modules, MainActivity (Compose host), navigation
data/
  db/           Room: entities, DAOs, BlockerDatabase, type converters
  datastore/    AppSettings (DataStore)
  repo/         ProfileRepository, AppRepository, StatsRepository, SettingsRepository
domain/
  model/        Clean domain models (Profile, Condition, BlockTarget, Decision…)
  engine/       BlockingEngine, ConditionEvaluator, ProfileActivation (PURE, unit-testable)
  usecase/      CreateProfile, ToggleProfile, EvaluateForeground, …
service/
  accessibility/  BlockerAccessibilityService, UrlExtractor, SettingsGuard, EjectController
  overlay/        OverlayController, BlockScreen (Compose-in-overlay)
  monitoring/     ForegroundService (specialUse), UsageStatsSampler (fallback), Watchdog
  notifications/  NotificationBlockerService (NotificationListenerService) + monitor
  receivers/      Boot, UserPresent, PackageAdded, PowerConnection, NetworkState,
                  GeofenceTransition, TimeChanged, ExactAlarm
  conditions/     TimeScheduler(AlarmManager), GeofenceManager, WifiMonitor,
                  UsageLimitTracker, LaunchCountTracker, CounterResetWorker
  strictmode/     DeviceAdminReceiver, StrictModeController, UnlockGates
ui/
  onboarding/   permission wizard
  profiles/     list, editor (apps, websites, conditions, settings tabs)
  apppicker/    installed-app + website + keyword pickers
  stats/        usage dashboard
  strictmode/   setup + unlock screens
  components/   shared Compose
util/           clock abstraction, logging (tagged), extensions
```

---

## 6. Data Model (Room) — cleaned-up

Core tables (boolean = INTEGER, time = epoch millis):

**`profiles`**
- `id` PK, `title`, `emoji`
- `enabled`, `blockingMode` (BLOCKLIST / ALLOWLIST)
- `conditionTypesMask` (which conditions this profile uses — bitmask)
- `operator` (AND default; OR reserved)
- `dayFlags` (bitmask Mon=1…Sun=64) — for the Time condition
- `blockLaunch`, `blockNotifications`, `blockUnsupportedBrowsers`, `addNewApps`
- `isLocked`, `lockedUntil`, `pausedUntil`, `onUntil`, `lastStartTime`

**`profile_apps`** — `id`, `profileId`(FK,cascade), `packageName`, `enabled`
**`profile_websites`** — `id`, `profileId`(FK), `value`, `matchType`(DOMAIN/KEYWORD/EXACT), `matchAnywhereInUrl`, `enabled`
**`time_intervals`** — `id`, `profileId`(FK), `fromMinuteOfDay`(0–1439), `toMinuteOfDay`, `enabled` (multiple per profile; wrap-past-midnight handled in logic)
**`wifi_networks`** — `id`, `profileId`(FK), `ssid`
**`geo_addresses`** — `id`, `profileId`(FK), `geofenceId`, `lat`, `lng`, `radiusMeters`, `inverted`, plus cached address text
**`usage_limits`** — `id`, `profileId`(FK), `periodType`(HOURLY/DAILY), `limitType`(TIME_MINUTES/LAUNCH_COUNT), `allowed`, `used`, `lastResetAt`
**`installed_apps`** (cache) — `packageName` PK, `label`, `labelNormalized`
**`browser_view_ids`** — `id`, `packageName`, `viewId`, so URL-bar node IDs are data, not hardcoded (a DB table allows new browsers to be supported without an app update)

Stats tables:
**`block_sessions`** — `id`, `targetName`, `type`(APP/WEBSITE), `timestamp` (what got blocked, when)
**`blocked_notifications`** — `id`, `packageName`, `profileId`, `title`, `text`, `timestamp`
**`usage_records`** / **`launch_times`** — for the statistics dashboard

**Key design idea:** a profile is **schedule-active** iff
`(liveConditionBits & conditionTypesMask) == conditionTypesMask` (all enabled conditions met).
Each condition subsystem flips its bit; the engine reads the combined mask. Clean, cheap, testable.

---

## 7. The Blocking Engine (the centerpiece)

`domain/engine/` is **pure Kotlin** (no Android imports) so it unit-tests on the JVM with a fake
`Clock`, fake location/wifi/charger state, and fake counters. Pipeline:

1. **Input:** a `ForegroundEvent(packageName, className, url?, now)` from the accessibility service.
2. **Profile activation:** for each enabled profile, compute whether it's schedule-active:
   - **Time:** is `now`'s weekday in `dayFlags` AND `now`'s minute-of-day inside any interval
     (with correct midnight-wrap)?
   - **Location/Wifi/Charger:** read the cached live bit (kept fresh by receivers — see §8).
   - **Usage Limit / Launch Count:** is the counter for this profile/target still under `allowed`?
   - Combine via the AND-mask rule above. Respect `pausedUntil` / `onUntil` overrides.
3. **Target match:** among schedule-active profiles, does the foreground package (or URL) fall in
   the block set (BLOCKLIST) or outside the allow set (ALLOWLIST)?
4. **Decision:** `Block(profile, reason)` or `Allow`. Produce a human-readable **reason string**
   (e.g. `blocked com.instagram.android via "Focus" [time 09:00–17:00 ∧ wifi:Office]`).
5. **Enforce:** on `Block` → show overlay + eject + (if enabled) suppress notifications; increment
   counters; log a `block_session`. On `Allow` → ensure overlay is hidden.

Every decision is logged under a stable logcat tag with its reason — this is the backbone of the
CLI test loop (§15).

---

## 8. Condition Subsystems

- **Time** — On profile save/boot, `TimeScheduler` arms `AlarmManager.setExactAndAllowWhileIdle`
  at each interval's next start/end (per active weekday). Alarm fire → recompute + re-arm.
  `TimeChangedReceiver` (`TIME_SET`, `TIMEZONE_CHANGED`) re-arms everything. Midnight-spanning
  intervals split logically across the day boundary.
- **Location** — `GeofenceManager` registers each `geo_address` with `GeofencingClient`
  (ENTER+EXIT, ~100 m responsiveness). `GeofenceTransitionReceiver` flips the LOCATION bit;
  `inverted` negates the sense (active when *outside*). Requires `ACCESS_FINE` +
  `ACCESS_BACKGROUND_LOCATION` (separate onboarding step). Re-register on boot.
- **Wi-Fi** — `WifiMonitor` via `ConnectivityManager.NetworkCallback` (modern replacement for the
  deprecated `WifiManager` SSID broadcast). On connect, compare SSID to `wifi_networks`; flip the
  WIFI bit. Off → bit cleared.
- **Usage Limit** — `UsageLimitTracker` accumulates *active* foreground seconds per profile-target
  (driven by accessibility foreground enter/leave timestamps). When `used ≥ allowed`, the limit is
  "exhausted" → profile becomes blocking. `CounterResetWorker` (WorkManager + exact alarm at the
  hour/day boundary) resets `used` and `lastResetAt`.
- **Launch Count** — increment when a target transitions *into* the foreground (debounced so quick
  re-entries don't double-count). Same reset machinery.
- **Charger** — `PowerConnectionReceiver` (`ACTION_POWER_CONNECTED/DISCONNECTED`) flips the CHARGER bit.

---

## 9. Enforcement

- **Overlay** — `TYPE_APPLICATION_OVERLAY` (API 26+), `MATCH_PARENT`×`MATCH_PARENT`,
  `FLAG_NOT_FOCUSABLE`, translucent. Hosts a Compose block screen (app icon, profile name,
  reason/"blocked until", optional motivational text, optional grace "close" button). Single
  instance guarded by a held view reference; handle `BadTokenException` defensively.
- **Eject** — launch HOME via `Intent(ACTION_MAIN){addCategory(HOME); FLAG_ACTIVITY_NEW_TASK}`,
  with `performGlobalAction(GLOBAL_ACTION_HOME)` as a backup. (We do **not** kill processes.)
- **Notification blocking** — `NotificationBlockerService : NotificationListenerService`. While a
  profile with `blockNotifications` is active and the posting package is in its block set,
  `cancelNotification(key)`. A monitor (WorkManager) calls `requestRebind()` if the listener drops.

---

## 10. Website & Keyword Blocking

- The accessibility service watches the ~40 known browser packages (data-driven via
  `browser_view_ids`). On a content-changed event it reads the URL-bar node via
  `findAccessibilityNodeInfosByViewId("<browser pkg>:id/url_bar")`, extracts the host with a regex,
  and asks the engine if the host/URL is blocked.
- **Match types:** DOMAIN (host + subdomains), EXACT, KEYWORD (substring anywhere in URL; later,
  optionally page title). 
- **Block action:** redirect the tab to a bundled `blank.html` via the `ACTION_SET_TEXT`
  accessibility action on the URL bar (API 30+) or an `ACTION_VIEW` intent (older) — the standard approach, gentler than overlaying the whole browser.
- **Block unsupported browsers:** if the foreground browser isn't in the supported view-ID set and
  the profile has `blockUnsupportedBrowsers`, block the **entire browser** (overlay) since its URL
  can't be read.

---

## 11. Strict Mode & Anti-Circumvention

Escalating, opt-in, gated by an **unlock condition** chosen at setup (PIN, timer, charger,
follow-schedules, or combinations — several are possible):

- **L1 — Lock config:** while a profile is active, block editing/deleting it and disabling the app.
- **L2 — Block uninstall:** enable `BlockerDeviceAdminReceiver` (Device Admin). Android then refuses
  to uninstall the app; deactivating the admin is gated behind the unlock condition.
- **L3 — Block device Settings:** accessibility detects `com.android.settings` foreground and
  ejects, **whitelisting** a small set of allowed sub-screens (connections, Bluetooth, sound,
  language & input). **Decision: scope this to Pixel/AOSP and do it well** — both the emulator and
  the physical device are Pixel/AOSP, so we maintain one accurate AOSP `com.android.settings`
  activity/fragment whitelist rather than chasing Samsung/OnePlus/MIUI variants. Other OEMs get a
  clear "not supported on your device" message instead of a half-working brittle list.
- **Anti-tamper:** detect the Recents screen, the per-app "force stop"/FGS task-manager screen, and
  the Accessibility settings screen, and eject — to make disabling the service harder. (Documented
  as best-effort; the OS is the ultimate authority.)
- **Unlock gates** (`UnlockGates`): PIN hashed in `EncryptedSharedPreferences`; timer = wait out a
  countdown; charger = must be plugged in; follow-schedules = only unlockable when no profile active.

> Honesty note: L3 and anti-tamper are the least portable, most maintenance-heavy parts. They'll be
> built last and scoped to Pixel/AOSP, with clear "this may not cover your OEM" UX.

---

## 12. Persistence & Reliability

- **Boot** — `BootReceiver` (`BOOT_COMPLETED`) re-arms alarms, re-registers geofences, restarts the
  monitoring foreground service if any profile is enabled.
- **Foreground service** — a `specialUse`-typed FGS (API 34 requirement) hosts the fallback sampler
  and keeps the process warm with a persistent notification. The accessibility service itself is
  kept bound by the system.
- **Watchdog** — periodic `WorkManager` job verifies the accessibility + notification listeners are
  connected; toggles component-enabled / `requestRebind()` to revive them.
- **Battery** — onboarding requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and guides OEM autostart
  settings, or background work gets dozed.
- **New-app blocking** — `PackageAddedReceiver` (`PACKAGE_ADDED`) adds qualifying new installs to
  profiles flagged `addNewApps`.

---

## 13. Statistics

From `block_sessions`, `usage_records`, `launch_times`, `blocked_notifications`: screen-time per
app/day, most-blocked apps/sites, blocks-over-time, notifications suppressed. Compose dashboard with
day/week ranges. Foreground time is sampled by the same accessibility enter/leave timestamps the
usage-limit tracker already produces (one source of truth).

---

## 14. Permissions & Onboarding

A Compose wizard requesting, in order, with rationale screens and a "test it" step:
1. **Usage access** (`PACKAGE_USAGE_STATS`) — Settings deep-link.
2. **Accessibility service** — Settings deep-link + a live "blocking test" (a sample
   overlay confirms it works).
3. **Display over other apps** (`SYSTEM_ALERT_WINDOW`).
4. **Notifications** (`POST_NOTIFICATIONS`) + **notification access** (listener) if used.
5. **Location** (fine → background, staged) — only if a geofence condition is added.
6. **Exact alarms** (`SCHEDULE_EXACT_ALARM`) — if time conditions used.
7. **Battery-optimization exemption.**
8. **Device Admin** — only when enabling Strict Mode L2.

Each gated behind the feature that needs it (don't ask for location up front).

---

## 15. Testing Strategy (CLI / logcat-first, per your workflow)

The engine is built to be tested **without** screenshots wherever possible:

1. **Pure unit tests (JVM, fast):** `BlockingEngine`/`ConditionEvaluator` against a fake `Clock`,
   fake location/wifi/charger/counter inputs. Covers all condition logic + AND-combination +
   midnight-wrap + allow/block-list + usage/launch limits. `./gradlew test`.
2. **Decision logging:** every foreground event + decision + **reason** logged under stable tags
   (`BlockEngine`, `FgDetect`, `Overlay`, `Cond`). The primary manual signal is
   `adb logcat -s BlockEngine:* FgDetect:*` while driving the device.
3. **A debug-only condition-injection harness:** a `debug`-build broadcast receiver / content
   provider that injects **fake time, fake location, fake wifi SSID, fake charger, fake counters**,
   so I can exercise every condition deterministically from the CLI without physically moving,
   switching networks, or waiting for clocks. Huge for testing geofence/time logic on an emulator.
   Example: `adb shell am broadcast -a com.customblocker.debug.SET_WIFI --es ssid Office`.
4. **Driving the device from CLI:** `adb shell am start -n <pkg>/<activity>` (or `monkey -p <pkg> 1`)
   to bring a target app forward, then assert the overlay appeared via
   `adb shell dumpsys window windows | grep <overlay>` and the logcat decision line.
5. **Instrumented tests** (`androidTest`, run on the emulator) for Room DAOs, the accessibility URL
   extractor against captured node dumps, and overlay add/remove.
6. **Screenshots = last resort** — only to eyeball the block screen / Compose UI polish.

Exit criteria for each phase are written as a concrete CLI assertion (see roadmap).

---

## 16. Phase 0 — Build & Dev Environment (one-time)

1. Install Android **command-line tools** + SDK packages (`platform-tools`, `platforms;android-35`,
   `build-tools;35.x`, `cmdline-tools;latest`, an emulator system image with **Google APIs** —
   needed for geofencing — e.g. `system-images;android-34;google_apis;x86_64`), and `emulator`.
   Likely via `yay`/AUR or Google's zip; `ANDROID_HOME` exported in profile.
2. Create the AVD on the **Pixel 4 XL** hardware profile (matches the physical device) with that
   Google-APIs image; boot **headless** (`-no-window -gpu swiftshader_indirect`) and confirm
   `adb`/logcat reachable.
3. Scaffold the Gradle project (Kotlin DSL, Compose BOM, Hilt, Room, WorkManager, Play-Location,
   DataStore), wire `minSdk 26` / `targetSdk 35`, confirm it builds and `adb install`s to the AVD.
4. Establish the build→install→logcat loop as a one-command script.

---

## 17. Phased Roadmap (each phase ends with a CLI-verifiable milestone)

| Phase | Deliverable | Exit criterion (CLI) |
|---|---|---|
| **0** | SDK + Pixel 4 XL AVD + buildable Compose skeleton | `gradlew installDebug` puts the app on the booted AVD |
| **1** | **Core engine MVP**: accessibility foreground detection + overlay + eject for a hardcoded block set; permission onboarding for accessibility + overlay | Launch Chrome on AVD → `logcat` shows `Block ...` + overlay window present in `dumpsys window`; user ejected to home |
| **2** | Room schema + repositories + app/website/keyword pickers + block-list/allow-list UI; engine reads DB snapshot via Flow | Add an app in UI → it's blocked with **no rebuild**; remove → allowed |
| **3** | **Conditions**: Time → Wi-Fi → Usage-limit → Launch-count → Location(geofence, inverted) → Charger; AND-combination; the condition-injection test harness | Inject fake wifi/time/location via `adb` broadcasts → engine flips block decision correctly per unit-tested truth table |
| **4** | Per-profile **block-launch / block-notifications**; **website + keyword blocking** in browsers; block-unsupported-browsers; auto-block new apps | Browse to a blocked domain → redirected to blank page; blocked app's notification cancelled (`dumpsys notification`) |
| **5** | Persistence & reliability: boot re-arm, FGS, watchdog, battery-exemption onboarding | Reboot AVD with active profile → blocking resumes automatically |
| **6** | **Strict Mode**: L1 lock-config → L2 device-admin uninstall block → L3 settings block (Pixel/AOSP whitelist) + unlock gates (PIN/timer/charger) | Enable L2 → uninstall refused; open Settings under L3 → ejected except whitelisted screens |
| **7** | **Statistics** dashboard (Compose charts) | Usage/blocks recorded and rendered for a day/week range |
| **8** | Polish: theming, edge cases, OEM notes, full onboarding wizard | — |

Phases 0–1 are make-or-break; everything after is conventional app work layered on the proven engine.

---

## 18. Risks & Open Decisions

- **Accessibility-disable resistance** is inherently limited — set expectations in UX.
- **OEM Settings-whitelist coverage** (L3) — **decided: Pixel/AOSP only**, done accurately; other
  OEMs explicitly unsupported for this one feature (rest of the app is OEM-agnostic).
- **Background-location reliability** varies; geofencing may lag on dozing OEMs.
- **Play Store policy** (accessibility + special-use FGS justification) — irrelevant for sideload,
  but noted if distribution is ever wanted.
- **Open decision:** single-process (chosen for v1) vs separate accessibility process (more
  kill-resilient, much more complex) — revisit only if real-world kills prove a problem.
- **Open decision:** keep the AND-only operator for v1, or expose OR/condition-groups (some blockers
  keep an `operator` field but are essentially AND in practice). Recommend AND-only first.
