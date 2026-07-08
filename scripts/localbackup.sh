#!/usr/bin/env bash
# Local, device-side backup/restore for Custom Blocker's app-private data.
#
# Uses `run-as` (works because the debug build is debuggable) to tar the app's
# private data straight off the device — no cloud, no Google Auto Backup, no
# signing-key coupling. A backup made this way restores to ANY install of the
# same package on a debuggable build, regardless of signing key.
#
# Why this exists: Google Auto Backup is encrypted and gated on the *signing
# key* matching at restore time, and its cloud reference can be dropped by
# backup-state changes. These local tarballs are yours, unconditionally.
#
# Usage:
#   scripts/localbackup.sh backup            # snapshot -> backups/<pkg>-<UTCstamp>.tar.gz
#   scripts/localbackup.sh restore <file>    # force-stop app, replace its data, from <file>
#   scripts/localbackup.sh list              # list local snapshots
#
# Honors ANDROID_SERIAL (falls back to the sole attached device).

set -euo pipefail

PKG="${CB_PKG:-com.customblocker.debug}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
BK_DIR="${CB_BACKUP_DIR:-$PROJECT_DIR/backups}"

# Data subdirs worth capturing. Skip cache/code_cache (regenerable) and no_backup
# (Android excludes it from Auto Backup by design — e.g. WorkManager's job queue;
# restoring it can re-fire stale scheduled work).
DATA_PATHS="files databases shared_prefs"

_adb() { adb "$@"; }

_require_app() {
  _adb shell pm list packages 2>/dev/null | grep -q "^package:$PKG$" \
    || { echo "ERROR: $PKG not installed on device" >&2; exit 1; }
  _adb shell run-as "$PKG" true 2>/dev/null \
    || { echo "ERROR: run-as $PKG failed (not a debuggable build?)" >&2; exit 1; }
}

# Space-separated list of the DATA_PATHS that actually exist for this install.
# NOTE: a *direct* `run-as PKG <cmd>` runs with cwd = the app data dir, but
# `run-as PKG sh -c ...` resets cwd to /. So enumerate with a direct `ls`.
_existing_paths() {
  local listing; listing="$(_adb shell run-as "$PKG" ls -a 2>/dev/null | tr -d '\r')"
  local present=""
  for p in $DATA_PATHS; do
    if printf '%s\n' "$listing" | grep -qx "$p"; then present="$present $p"; fi
  done
  echo "$present" | sed 's/^ *//'
}

backup() {
  _require_app
  mkdir -p "$BK_DIR"
  local paths; paths="$(_existing_paths)"
  if [ -z "$paths" ]; then
    echo "Nothing to back up yet (no files/databases/shared_prefs) — open the app and create data first." >&2
    exit 1
  fi
  # UTC timestamp from the DEVICE (host clock policy elsewhere prefers device framebuffer/time).
  local stamp; stamp="$(_adb shell date -u +%Y%m%dT%H%M%SZ 2>/dev/null | tr -d '\r')"
  local out="$BK_DIR/${PKG}-${stamp}.tar.gz"
  echo "Backing up [$paths] from $PKG ..."
  # tar on device (toybox) -> stdout -> gzip on host. Portable across One UI toybox versions.
  _adb exec-out run-as "$PKG" tar -c $paths 2>/dev/null | gzip > "$out"
  if [ ! -s "$out" ]; then rm -f "$out"; echo "ERROR: backup produced no data" >&2; exit 1; fi
  echo "Saved: $out ($(du -h "$out" | cut -f1))"
  echo "Contents:"; tar -tzf "$out" | sed 's/^/  /' | head -40
}

restore() {
  local file="${1:-}"
  [ -n "$file" ] && [ -f "$file" ] || { echo "usage: $0 restore <file.tar.gz>" >&2; exit 1; }
  _require_app
  echo "Force-stopping $PKG ..."
  _adb shell am force-stop "$PKG"
  echo "Restoring $file into $PKG data dir ..."
  # Binary stdin through `adb shell` is unreliable (PTY line-ending translation),
  # so stage a plain (host-decompressed) tar in world-readable /data/local/tmp,
  # then extract with a *direct* run-as tar (cwd = data dir), and clean up.
  local tmp_host tmp_dev="/data/local/tmp/.cb_restore_$$.tar"
  tmp_host="$(mktemp)"; trap 'rm -f "$tmp_host"' RETURN
  gzip -dc "$file" > "$tmp_host"
  _adb push "$tmp_host" "$tmp_dev" >/dev/null
  _adb shell run-as "$PKG" tar -x -f "$tmp_dev" 2>/dev/null || {
    _adb shell rm -f "$tmp_dev" >/dev/null 2>&1; echo "ERROR: restore extraction failed" >&2; exit 1; }
  _adb shell rm -f "$tmp_dev" >/dev/null 2>&1
  echo "Done. Relaunch the app to verify."
}

list() {
  mkdir -p "$BK_DIR"
  echo "Local snapshots in $BK_DIR:"
  ls -1t "$BK_DIR"/*.tar.gz 2>/dev/null | sed 's/^/  /' || echo "  (none yet)"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  backup)  backup "$@";;
  restore) restore "$@";;
  list)    list "$@";;
  *) echo "usage: $0 {backup|restore <file>|list}" >&2; exit 1;;
esac
