#!/usr/bin/env bash
# Dev helper for the Custom Blocker project.
# Encodes the build -> install -> drive -> observe loop from PLAN.md §15-16.
#
# Usage:  source scripts/dev.sh   (then call the functions)  OR  scripts/dev.sh <cmd>
#
# Screenshot policy: capture the EMULATOR FRAMEBUFFER ONLY via adb (never the
# host desktop), because the user may switch what's on their physical screen.

set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
# avdmanager (XDG-aware) writes AVDs here; point the emulator at the same place.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

AVD_NAME="pixel4xl_api35"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
APP_ID="com.customblocker.debug"

avd_create() {
  if avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
    echo "AVD $AVD_NAME already exists"; return 0
  fi
  echo "no" | avdmanager create avd -n "$AVD_NAME" \
    -k "system-images;android-35;google_apis;x86_64" \
    --device "pixel_4_xl" --force
}

emu_start() {
  if adb devices | grep -q emulator; then echo "emulator already running"; return 0; fi
  nohup emulator -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -no-snapshot >/tmp/emulator.log 2>&1 &
  echo "emulator starting (pid $!), log: /tmp/emulator.log"
}

emu_wait() {
  adb wait-for-device
  echo "waiting for boot..."
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
  adb shell input keyevent 82 >/dev/null 2>&1 || true  # dismiss keyguard
  echo "boot complete"
}

build()    { cd "$PROJECT_DIR" && ./gradlew :app:assembleDebug; }
install()  { cd "$PROJECT_DIR" && ./gradlew :app:installDebug; }
run()      { adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null; }
unit()     { cd "$PROJECT_DIR" && ./gradlew :app:testDebugUnitTest; }

# Tagged decision logs are the PRIMARY test signal (see PLAN.md §15).
logcat()   { adb logcat -s "BlockEngine:*" "FgDetect:*" "Overlay:*" "Cond:*" "${@:-}"; }

# Bring a target app to the foreground from CLI (for blocking tests).
launch_pkg() { adb shell monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null; }

# Emulator-framebuffer screenshot ONLY. Arg: output path (default /tmp/emu.png).
shot()     { adb exec-out screencap -p > "${1:-/tmp/emu.png}"; echo "saved ${1:-/tmp/emu.png}"; }

# Verify an overlay window is present (CLI assertion, no screenshot needed).
overlay_present() { adb shell dumpsys window windows | grep -i "overlay" || echo "no overlay window"; }

"${@:-}"
