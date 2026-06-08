# Development Log

Chronological record of decisions and progress, so any new chat/session can pick up with full
context. Newest entries at the top. Pairs with [PLAN.md](PLAN.md) (the what/why) — this is the
when/how-it-went. Git history is the source of truth for code; this log captures intent and state.

**Conventions**
- Each phase is developed on a branch `phaseN-<slug>`, merged to `main` (no-ff) when its
  PLAN.md exit criterion is met. `main` always builds.
- Commits are small and message-rich; commit at each meaningful checkpoint.
- Tagged logcat (`BlockEngine`/`FgDetect`/`Overlay`/`Cond`) is the primary test signal.
- Screenshots: emulator framebuffer only (`scripts/dev.sh shot`), never the host desktop.

---

## 2026-06-08 — Deferred-items sweep (Phase 4–6 follow-ups)

**Branch:** `deferred-items` → merged to `master` (`4fa5dfe`). **State:** ✅ COMPLETE, all verified.
Picked up every previously-deferred item.

**D-1 per-profile behavior flags** (`fef49c2`): Room **v6** adds `blockLaunch` (default true),
`blockNotifications`, `addNewApps` to ProfileEntity. `ProfileRule.targets()` extracted; `decide`
respects `blockLaunch` (a profile can be notifications-only); new `shouldBlockNotification(pkg,ctx)`.
Editor gains a "Behavior" section with the 3 toggles (lock-gated). 3 engine unit tests.

**D-2 block notifications** (`0d8362d`): `BlockerNotificationListener` (NotificationListenerService)
cancels notifications from targeted apps when an active profile has `blockNotifications` on. Onboarding:
`Permissions.isNotificationAccessGranted` + a "Grant notification access" button in the editor.
*Verified:* a posted notification from a targeted package was suppressed + removed from the shade.

**D-3 auto-block new apps** (`0dfc7e1`): `PackageAddedReceiver` adds fresh launchable installs to
`addNewApps` profiles. KEY GOTCHA: **ACTION_PACKAGE_ADDED is NOT exempt from the O+ manifest
implicit-broadcast ban** — a manifest receiver never fires (confirmed: GMS etc. got it, ours didn't).
Fixed by **registering at runtime** from the Application (process kept alive by the FGS),
RECEIVER_NOT_EXPORTED. Build hook `-PidExtra=N` builds a second package to test real installs.
*Verified:* installing a fresh package auto-added it to the flagged profile.

**D-4 watchdog** (`9770280`): hourly `WatchdogWorker` (WorkManager) posts a "Blocking is paused"
notification if accessibility is disabled, clears it when healthy. *Verified:* with a11y off the
worker logged the detection + fired the alert. (Can't re-enable a11y programmatically — prompting is
the best option.)

**D-5 time-boundary alarms** (`6ccc130`): `TimeBoundaryScheduler` sets a `setAndAllowWhileIdle`
wake-up at the next TIME-window edge; on fire the a11y service re-evaluates + reschedules. Wired from
EngineSync on rule changes. *Verified:* alarm registered at the correct boundary; on fire logged
"time boundary reached — re-evaluating" + rescheduled. (Honest: marginal value — the 3s ticker
already covers the interactive case; while-idle alarms are rate-limited ~9–15 min so short test
boundaries get deferred. Real boundaries are far apart.)

**D-6 L3 anti-tamper +installer** (`1af7eec`): extended the L3 guard to block the package installer /
permission UI (com.android.packageinstaller, com.google.android.packageinstaller,
com.android.permissioncontroller). *Verified:* uninstall dialog ejected under L3+locked.

**Explicitly NOT chased (with rationale):** (a) L3 Settings sub-screen whitelist — leaks via generic
a11y classNames (proven in Phase 6). (b) Recents/notification-shade anti-tamper — those are
systemui/launcher (never-block; soft-brick risk), and swiping from Recents doesn't stop the system-
bound a11y service, so it's not a real bypass vector; the force-stop it reaches is in Settings,
already blocked. (c) Per-profile "active tie-in" for L1 — that would *weaken* L1 (currently locks
whenever strict is enabled), so left as-is.

## 2026-06-08 — Phase 6: Strict Mode (Pixel/AOSP)

**Branch:** `phase6-strict-mode` → merged to `master` (`44d520c`). **State:** ✅ COMPLETE, verified.
App-global, opt-in, escalating anti-circumvention gated by an unlock method.

**6-1 — config + screen** (`ce38d79`): `StrictModeStore` (DataStore): enabled / level(1–3) / gate
(NONE/PIN/TIMER/CHARGER) / timerMinutes; PIN stored only as a **salted SHA-256 hash** (`verifyPin`
re-hashes). `StrictModeScreen` (lock icon in home top bar) picks level + gate (+ set PIN / timer).

**6-2 — L1 lock config + unlock gates** (`75e166c`): `UnlockManager` holds an **in-memory** unlocked
session (resets on process death → app re-locks on restart). `LockViewModel` exposes
`locked = enabled && !unlocked`. While locked, the Strict screen, profile list (delete/enable), and
profile editor (blocking switch, mode chips, add/remove) **disable** their mutating controls and show
a `LockedBanner` → `UnlockDialog`. Gates: PIN (hash verify), TIMER (countdown), CHARGER (must be
plugged), NONE (confirm). **Lock-out guard:** PIN gate with no PIN set falls back to confirm.
Verified: enable → instant lock (disabled controls + banner); unlock re-enables; re-locks after
restart; PIN persists; PIN hash round-trip proven (`sha256(salt:pin)` matches stored, wrong rejected).

**6-3 — L2 device-admin uninstall block** (`f2c84ef`): `BlockerDeviceAdminReceiver` + `device_admin
.xml`; manifest receiver (BIND_DEVICE_ADMIN). L2+ card turns protection on (system activation
screen) / off (`removeActiveAdmin` on our own admin — unrestricted; gated behind unlock). Verified:
with admin active, even `adb uninstall` → `DELETE_FAILED_DEVICE_POLICY_MANAGER`; in-app Turn off
removes it and uninstall then succeeds (reversible — no permanent lock-out). Note: `dpm
remove-active-admin` from shell refuses non-test admins (SecurityException) — only our in-app path
removes it.

**6-4 — L3 block device Settings** (`1f47967`): while L3 active **and locked**, the a11y service
ejects from `com.android.settings` (overlay + home). Service collects `StrictModeConfig` + checks
`UnlockManager`, so unlocking restores Settings access. **Whitelist abandoned:** AOSP fires multiple
window-state events per screen with generic view classNames (`android.widget.FrameLayout`), not the
activity alias, so a className whitelist leaks — `SettingsGuard` blocks **all** of Settings (strict-
correct; unlock to reach Settings). Verified: locked+L3 → Settings ejected; unlocked → Settings opens.

**Honesty:** L3 is the brittle, Pixel/AOSP-only piece as flagged. Anti-tamper covers the high-value
targets (accessibility settings, force-stop, device-admin screens are all inside Settings). Recents/
task-manager detection not added. Unlock session is in-memory (process death re-locks); a determined
user with adb/root can still intervene — the OS is the ultimate authority.

## 2026-06-08 — Lock-out fix (between Phase 4 and 5)

**Commit `36588eb` on `master`.** User testing surfaced a real lock-out: in **allow-list** mode the
profile blocked everything not listed — including our own app — and the 3s mid-session ticker kept
re-evaluating a *stale* `trackedPkg` and ejecting to home every tick, trapping the user.
- `BlockingEngine.selfPackage` (set from the a11y service's `packageName`) is never blocked in any
  mode (both `decide` + `decideUrl`).
- When our own **UI Activity** comes foreground: hide overlay, finalize usage, clear `trackedPkg`
  (ticker can't eject while editing). Gated on an Activity className — our **overlay window** also
  fires a window-state event under our package, and acting on it would hide the block screen we just
  raised (caused a 0.2s overlay flash; fixed). Verified: allow-list no longer ejects from our app;
  block screen now persists over the launcher until dismissed.

## 2026-06-08 — Phase 5: persistence & reliability

**Branch:** `phase5-reliability` → merged to `master` (`2e9e738`). **State:** ✅ COMPLETE, verified.

**Baseline first:** rebooted the AVD with an active profile — the system auto-rebinds the
accessibility service, which starts the process → `Application.onCreate` → `EngineSync`/
`SignalsCoordinator` (re-registers geofences) → engine reloads rules → youtube blocked + ejected.
So the PLAN exit criterion ("reboot → blocking resumes") already passes without extra infra. Phase 5
therefore targets the *non-automatic* durability gaps:

**5-2 — persist usage/launch counters** (`4f443b2`): Room **v5** `usage_counters` (profileId,
usedSeconds, launches, usagePeriodKey, launchPeriodKey). `UsageTracker` writes through on each
change and `load()`s on startup (from the Application); a counter whose stored period key has rolled
over resets to 0. Verified: seeded an over-limit counter with today's key → Chrome blocked on first
launch after restart; stale (yesterday) key → reset to 0, not blocked.

**5-1 — foreground service** (`afa47e3`): promoted the **already-bound accessibility service** to a
foreground service (specialUse) with a persistent "blocker active" notification (`StatusNotification`)
— rather than a separate monitoring service, which would hit Android 12+ background-FGS-start limits.
Raises process priority (resists low-memory kills), ties the notification to the real worker, and
needs no boot receiver: the a11y service auto-rebinds on boot and self-promotes. Manifest:
FOREGROUND_SERVICE(+SPECIAL_USE), POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, foregroundServiceType=
specialUse + PROPERTY_SPECIAL_USE_FGS_SUBTYPE. Best-effort try/catch. Verified on API 35:
`isForeground=true types=0x40000000`; after reboot the FGS re-promotes automatically.

**5-3 — battery-exemption onboarding** (`72dd174`): a soft home-screen `ReliabilityBanner` (not a
hard gate) checks `PowerManager.isIgnoringBatteryOptimizations` and opens the system exemption
dialog; hidden once granted. Verified the full round-trip on the emulator.

**Decisions:** separate BootReceiver/MonitoringService omitted as redundant (a11y auto-resume covers
it). Time-boundary alarms not added — the 3s ticker already re-evaluates the foreground app, catching
window start/end within 3s while interactive; waking from idle to *start* a block is a later refinement.
Watchdog (WorkManager revive of a11y) deferred — the system rebinds reliably on this target.

## 2026-06-08 — Phase 4: website & keyword blocking

**Branch:** `phase4-websites` → merged to `master` (`fce75e2`). **State:** ✅ COMPLETE, verified.
Done in three committed sub-steps:

**4-1 — engine + schema** (`0dfcfb1`): pure `domain/engine/WebsiteRules.kt` — `UrlMatchType`
(DOMAIN matches host + subdomains, KEYWORD = substring anywhere, EXACT = whole URL scheme-
insensitive), `UrlMatcher.host()` best-effort host extraction (strips scheme/www/userinfo/path,
rejects search queries with spaces or no dot) + `normalize()`; `WebsiteRule` on `ProfileRule`;
`BlockingEngine.decideUrl(pkg, url, ctx)` (any active profile with a matching site blocks; unreadable
URL + `blockUnsupportedBrowsers` blocks the whole browser). Room **v4**: `profile_websites`
(profileId, value, matchType, enabled; unique profileId+value+matchType) + `profiles
.blockUnsupportedBrowsers`. Repo folds websites into the `profileRules` flow (3-arg 2nd-level
combine alongside usage limits). 17 JVM tests (matching + decideUrl + guards).

**4-2 — read browser URL bar** (`ead4c0b`): `BrowserUrlReader` maps ~13 known browsers
(chrome/beta/dev, brave, firefox, focus, edge, opera/mini, duckduckgo, samsung, vivaldi, kiwi) to
their URL-bar view id and reads text via `findAccessibilityNodeInfosByViewId`. Service now also
handles `TYPE_WINDOW_CONTENT_CHANGED` for browsers (deduped by `lastUrl`), calls `decideUrl` →
shared `enforceBlock` (overlay + HOME eject). New `UrlDetect` log tag. `evaluate()` returns Boolean
(blocked app skips URL read).

**4-3 — picker UI** (`40cc171`): `ui/websites/WebsiteScreen` + `WebsiteViewModel` — match-type
chips (Domain/Keyword/Exact), add/list/remove, **block-unsupported-browsers** toggle. Entry button
in the profile editor; `websites/{profileId}` route in AppNav.

**Verified on emulator** (real device, not unit-only): seeded `youtube.com` DOMAIN → browsing to
`m.youtube.com` in Chrome logged `UrlDetect: BLOCK … site "youtube.com"`, showed overlay, ejected to
launcher; `example.com` logged `allow`, stayed in Chrome (no over-block). Drove the UI end-to-end:
profile editor → Websites & keywords → selected Keyword → typed "casino" → Add → "Blocked (2)" +
engine reload `sites=2`. Chrome gotchas: dismiss first-run via `--disable-fre` cmdline + tap the
"No thanks" notification modal; force-stop drops the a11y binding so re-set
`enabled_accessibility_services` after reinstall.

**Notes / deferred:** Other Phase-4 roadmap items NOT done yet — per-profile block-launch &
block-notifications (NotificationListenerService), auto-block newly-installed apps. Browser
view-id map is hardcoded (a DB table would allow updates without an app release); fine for v1.

## 2026-06-08 — Phase 3b: finish conditions (usage/launch limits, multi-profile, geofencing)

**Branch:** `phase3b-conditions-finish` → merged to `master`. **State:** ✅ COMPLETE, verified.
Done in three committed sub-steps:

**3b-1 — usage-time + launch-count limits** (`f9b7456`): domain specs + evaluator (over-limit =
condition met) + 3 unit tests (19 total); Room v3 `usage_limits`; `UsageTracker` (in-memory
per-profile accumulators, hourly/daily resets) driven by the a11y service (usage timing on
foreground change + launch count per open) plus a 3s ticker for mid-session enforcement;
ContextProvider feeds counters into the engine; conditions UI sections. Verified: launch 5/day
blocks on 5th open; usage 1-min accrues live (2,5,8,…) and blocks once cumulative ≥60s.
*Counters are in-memory until Phase 5.*

**3b-2 — multi-profile** (`b6f292f`): repo + ViewModels are profileId-scoped (SavedStateHandle nav
arg); new `ProfileListScreen` home (enable/delete/create); `BlockListScreen` is the per-profile
editor; routes carry `{profileId}`. Verified: 2nd profile "Focus" → engine logs 2 independent
profiles.

**3b-3 — real geofencing + map** (this commit): osmdroid map picker (keyless OSM tiles, tap-to-pin,
radius slider, invert) → `addGeo` + auto-enables LOCATION; `GeofenceManager` registers real
`GeofencingClient` geofences (re-synced via `SignalsCoordinator` on row changes);
`GeofenceTransitionReceiver` updates `LiveSignals.insideGeofenceIds`; location perms +
request flow. Verified: map renders OSM tiles; saving a pin → geo row + `Default` mask=24
(usage∧location) + log `geofences registered: [geo_1_…]`. Engine inside/outside/inverted logic was
already unit-tested + harness-proven in Phase 3; real GPS-transition firing needs emulator
mock-location (best-effort, not hardware-verified here).

## 2026-06-08 — Phase 3: conditions engine (time / wifi / charger / location)

**Branch:** `phase3-conditions` → merged to `master`. **State:** ✅ COMPLETE, verified on emulator.

Made blocking conditional. A profile is schedule-active when ALL its enabled conditions are met
(AND); a package is blocked if ANY active profile blocks it (OR). Engine is multi-profile-ready.
- `domain/engine/Conditions.kt`: pure `TimeWindow` (wraps midnight), `GeofenceSpec` (inverted),
  `ProfileRule`, `BlockContext`, `ConditionTypes`/`DayFlags` bitmasks, `ConditionEvaluator`.
  `BlockingEngine` now holds `List<ProfileRule>` and `decide(pkg, ctx)`. 16 JVM unit tests (truth
  table: each condition, AND combos, multi-profile OR, midnight-wrap, inverted geofence, guards).
- Room v2: `time_intervals`, `wifi_networks`, `geo_addresses` + profile cols `conditionsMask`,
  `dayFlags`. `BlockerRepository.profileRules(): Flow<List<ProfileRule>>` (combine of 5 flows) +
  condition CRUD. `EngineSync` now streams the rule list.
- Live signals: `service/conditions/` — `LiveSignals` holder, `ContextProvider` (clock + signals,
  honours debug overrides), `ChargerMonitor` (receiver), `WifiMonitor` (NetworkCallback;
  SSID needs location perm on API27+ → harness covers testing). Started from Application via the
  Hilt EntryPoint. A11y service builds ctx per foreground event and calls `decide(pkg, ctx)`.
- Debug injection harness (`src/debug` only): `DebugSignalReceiver` — adb broadcasts force
  wifi/charging/time/geofence overrides (SET_WIFI/SET_CHARGING/SET_TIME/SET_GEOFENCE/CLEAR).
- UI: `ConditionsScreen` (enable switches per condition, day chips, time-window picker, wifi SSID
  list, charger toggle) + ViewModel; reachable from home via a "Conditions" button.

Verified on emulator: added Chrome (apps=1); enabled Charger condition via UI (mask=4); harness
`SET_CHARGING false` → Chrome ALLOWED; `SET_CHARGING true` → Chrome BLOCKED + ejected. Exit
criterion met (injected signal flips decision per the unit-tested truth table).

**Scoped/deferred (stated to user):** USAGE_LIMIT + LAUNCH_COUNT (need a usage-accumulation
subsystem); real geofence registration (GeofencingClient) + map-picker UI (engine + injection
support LOCATION already); multi-profile management UI (engine/data already multi-profile).
Also: time-window picker dial wasn't automation-tested (clock-dial taps are impractical headless)
— logic is unit-tested; manual check pending. Mid-session time-boundary re-eval not yet wired
(blocking is evaluated on foreground events) — alarms come in the persistence phase.

## 2026-06-08 — Phase 2: data model + configurable block list

**Branch:** `phase2-data-model` → merged to `master`. **State:** ✅ COMPLETE, verified on emulator.

Replaced the hardcoded block set with a DB-backed, UI-editable list streamed to the engine:
- `data/db`: Room (`profiles`, `profile_apps`) + `BlockerDao` + `BlockerDatabase` (v1,
  destructive-migration in dev). One implicit "Default" profile in Phase 2; multi-profile is P3.
- `data/repo/BlockerRepository`: `engineSnapshot(): Flow<EngineSnapshot>` (enabled+mode+packages
  via flatMapLatest over the first profile), plus add/remove/setEnabled/setMode + ensureDefaultProfile.
- Engine refactor: `BlockingEngine` now evaluates an atomic `EngineSnapshot` (enabled + BLOCKLIST/
  ALLOWLIST mode + apps) instead of a raw set; `neverBlock` guards systemui/launchers in both modes.
  8 JVM unit tests (both modes, disabled, null, never-block) green.
- `EngineSync` (@Singleton): collects `repo.engineSnapshot()` → `engine.update()`; started from
  `CustomBlockerApp.onCreate` via a Hilt `@EntryPoint`. Single-process, so the a11y service reads
  the same engine singleton.
- UI: `BlockListScreen` (master switch, Block/Allow chips, app list w/ remove, FAB) +
  `AppPickerScreen` (searchable, checkbox add/remove) + `AppLister` (PackageManager launchables) +
  Hilt ViewModels + `AppNav` (Navigation-Compose). Onboarding became a `PermissionBanner` on home.

Verified (CLI + uiautomator-driven taps, headless): empty list → Chrome ALLOWED; tapped Chrome in
picker → `snapshot updated … apps=1` → Chrome BLOCKED + ejected (no rebuild); unchecked Chrome →
`apps=0` → Chrome ALLOWED again. Exit criterion met.

Gotchas: `fallbackToDestructiveMigration(dropAllTables=true)` is Room 2.7 — 2.6.1 needs the no-arg
form. Driving Compose via blind `input tap` is unreliable; use `uiautomator dump` to get exact node
bounds (the `input text` into the search field also didn't focus reliably — node-bounds taps work).

## 2026-06-08 — Phase 1: core blocking engine MVP

**Branch:** `phase1-core-engine` → merged to `main`. **State:** ✅ COMPLETE, verified on emulator.

Built the two pillars + a pure decision core:
- `domain/engine/BlockingEngine` — pure Kotlin, JVM-unit-tested (`BlockingEngineTest`, 5 tests
  green). `decide(pkg) -> Block/Allow`. Phase-1 block set is a settable `blockedPackages`
  (seeded with `com.android.chrome`); has a `neverBlock` guard for systemui/launchers. Phase 2
  swaps the set for a DB snapshot; the `decide` contract stays.
- `service/accessibility/BlockerAccessibilityService` — `@AndroidEntryPoint`; on
  `TYPE_WINDOW_STATE_CHANGED` resolves the foreground pkg, calls the engine, and on Block shows
  the overlay + ejects (`GLOBAL_ACTION_HOME` + HOME intent). Self-package + dup-package filtered.
- `service/overlay/OverlayController` — `TYPE_APPLICATION_OVERLAY`, MATCH_PARENT, NOT_FOCUSABLE,
  programmatic dark block View with a Close button. (Compose-in-overlay deferred to polish.)
- `ui/onboarding/` — Compose permission gate (accessibility + overlay) with ON_RESUME refresh +
  Settings deep-links. Manifest: SYSTEM_ALERT_WINDOW, QUERY_ALL_PACKAGES, the a11y service +
  `res/xml/accessibility_service_config.xml`.

Verified (CLI, no manual taps): granted overlay via `appops`, enabled a11y via
`settings put secure enabled_accessibility_services …`, launched Chrome →
`FgDetect: foreground=com.android.chrome` → `BlockEngine: BLOCK … in block list` →
`Overlay: shown` → foreground became launcher (ejected); overlay window present in
`dumpsys window`; block screen screenshot confirmed. Exit criterion met.

Gotcha: `google_apis` image *does* include `com.android.chrome` (good — our canonical target).

Next (Phase 2, new branch): Room schema + repositories + app/website/keyword pickers +
block/allow-list UI; engine reads a DB-backed snapshot via Flow instead of the hardcoded set.

## 2026-06-08 — Phase 0: environment + scaffold

**State:** ✅ COMPLETE. App builds, installs, and launches on the Pixel 4 XL emulator
(`emulator-5554`); Compose home screen renders with Material3 dynamic theme. Exit criterion met.

Gotchas hit & fixed:
- `ksp.useKSP2=true` broke Hilt 2.52 annotation processing (`kspDebugKotlin` →
  `PROCESSING_ERROR`). Removed the flag → KSP1 path → green build. Keep KSP2 off until Hilt
  officially supports it.
- Two adb devices attached (physical Pixel + emulator). Always target the emulator via
  `ANDROID_SERIAL`/`adb -e` (handled in `scripts/dev.sh`).

Goal = SDK + Pixel 4 XL emulator + a Compose skeleton that builds & runs.

Done:
- Surveyed the observable behavior of existing blockers and wrote the full `PLAN.md` (8 phases).
- Android SDK installed at `~/Android/Sdk` (platform-tools 37, emulator 36.6, platform/build-tools
  35, `system-images;android-35;google_apis;x86_64`). License accepted.
- Project scaffold committed (`0e1a78c`): AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, Hilt,
  Room, WorkManager, DataStore, Play-Location via version catalog; Gradle wrapper 8.10.2; Hilt
  `Application`, Compose `MainActivity`, Material3 theme, adaptive icon. `com.customblocker`,
  minSdk 26 / targetSdk 35. Debug build id suffix `.debug`.
- AVD `pixel4xl_api35` created (Pixel 4 XL profile, API 35 google_apis). Note: avdmanager writes to
  `~/.config/.android/avd` (XDG) but the emulator defaults to `~/.android/avd` — fixed by exporting
  `ANDROID_AVD_HOME` in `scripts/dev.sh`.
- `scripts/dev.sh` dev-loop helper (avd/emu/build/install/logcat/shot/overlay_present).

Key decisions locked (see PLAN §3-4, §11, §18):
- Kotlin + Compose; single Gradle module; **single-process** engine for v1 (rather than a separate
  `:AccessibilityService` process); **AND-only** conditions for v1; Strict-Mode "block Settings"
  scoped to **Pixel/AOSP only**. Engine (`domain/engine`) will be **pure Kotlin / JVM-unit-testable**.

Next: confirm first build is green + app launches on the emulator (Phase 0 exit), then branch
`phase1-core-engine` for the AccessibilityService + overlay + eject MVP.

Environment quick-ref:
- `ANDROID_HOME=~/Android/Sdk`, `ANDROID_AVD_HOME=~/.config/.android/avd`, JDK 21, Arch Linux, KVM ok.
- Physical device: Pixel 4 XL (`coral`), `adb` id `98201FFBA004WW` (used only to pull the APK).
