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
