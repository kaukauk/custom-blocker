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
