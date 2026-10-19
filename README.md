# Custom Blocker

A personal Android app for blocking distracting apps, websites and notifications. Each block is
tied to rules: when, where and how much. It also has a Strict Mode that makes the blocks hard to
undo in a weak moment.

I built it for my own phone, a Pixel running stock Android, so it's tuned to how I use my phone
rather than being a general product. It works fully offline: no account, no cloud and no
analytics. All data stays on the device.

---

## What it does

### Profiles

The app is organised around **profiles** (for example "Work hours" or "Night"). Each profile has:

- **Apps to block.** Pick them from your installed apps, or type a name or package to add apps
  that have no launcher icon or aren't installed yet.
- **Websites and keywords to block.** Websites match a domain and its subdomains, or an exact
  URL. Keywords match anywhere in the URL.
- **Block list or allow list.** Block only the listed apps, or block everything *except* the
  listed apps.
- **Conditions** that decide when the profile is active (see below).
- **Notification handling.** While the profile is active, notifications from its apps can be
  hidden, or held back and delivered once the block lifts.

You can enable, disable or pause each profile on its own, for a set time or indefinitely. The
home screen shows each profile's conditions and live usage, and marks a profile that has run out
of allowed time.

### Conditions

A profile blocks only when **all** of its enabled conditions are met. An app is blocked if
**any** active profile blocks it.

| Condition | What it means |
|---|---|
| **Schedule** | One or more time windows on chosen weekdays. Windows can run past midnight. |
| **Location** | Inside (or, inverted, outside) a radius around a point picked on a map. Uses OpenStreetMap, so no API key is needed. |
| **Usage time limit** | Block once the profile's apps have been used for N minutes per day or per hour. Time spent on the profile's websites counts too. |
| **Launch limit** | Block after the apps have been opened N times per day or per hour. |
| **Charging** | Only while charging, or only while not charging. |

Usage time counts every app on screen, including split-screen and picture-in-picture. Counters
only increase while the profile's other conditions apply. Counters survive restarts and reboots,
and reset at a "day start" hour you choose.

### How a block looks

When a blocked app or site comes to the front, a full-screen block page covers it and you are
sent to the home screen. Website blocking reads the address bar of the common browsers: Chrome
(including Beta and Dev), Firefox, Firefox Focus, Brave, Edge, Opera, Opera Mini, DuckDuckGo,
Samsung Internet, Vivaldi and Kiwi. Other browsers can optionally be blocked outright, because
the app can't see which page they're on. If a blocked app is playing in picture-in-picture, its
playback is stopped.

Some things are never blocked, so a profile can't lock you out of the phone: this app itself,
the launcher, the keyboard, System UI, calls, biometric and credential prompts, and Wi-Fi sign-in
pages.

### Notification filters

Separate from profiles, you can write **regex rules** that match notifications by app, title
and/or content. Each rule has one action:

- **Dismiss**: remove the notification.
- **Catch**: remove it from the notification shade but keep it in the app's inbox.
- **Batch**: hold it and deliver held notifications together at a regular interval.
- **Delay**: hold it until set times of day.

The app keeps a history of recent notifications. It can suggest a regex from a notification you
tap, or pre-fill a new rule from it.

### Nothing-new pause

This is an optional reflex check. When you unlock the phone and **no notification arrived while
the screen was off**, the screen is held behind a short countdown (5 seconds to 5 minutes)
before you can use it. If something did arrive, you go straight in. Notifications that the
filters removed don't count as arriving.

### Strict Mode

Strict Mode is an opt-in setting that applies to the whole app and makes blocks harder to get
around. It has three levels, each adding to the one before:

1. **Lock config.** While locked, profiles can only be made *more* restrictive. You can add
   blocks, but you can't remove apps, loosen conditions, disable a profile or delete it.
2. **Block uninstall.** The app registers as a Device Admin, so Android refuses to uninstall it
   (even `adb uninstall` fails).
3. **Guard device Settings.** Blocks the Settings screens that could be used to turn the blocker
   off: Accessibility, Device admin, force-stop, Developer options and the package installer.
   You choose between two modes:
   - **Safe only (allow list).** Settings is replaced by a small launcher that offers only safe
     shortcuts such as Wi-Fi and Bluetooth.
   - **Block risky (block list).** Settings works normally, and only the dangerous screens are
     ejected.

To unlock Strict Mode, you pass the method you chose:

| Method | How it unlocks |
|---|---|
| **PIN** | Enter the PIN. If you forget it, you can unlock by waiting a set number of hours instead. |
| **Countdown** | Keep a countdown on screen until it finishes. Leaving the screen or turning it off restarts it. |
| **Timer** | A fixed commitment, such as 3 days, with no early unlock. As an optional escape hatch, you can *earn* a few minutes of unlock time by keeping a window open full-screen and waiting. |
| **Charger** | Unlock only while the phone is plugged in. |

### Statistics

The Statistics screen shows, for today or the last 7 days, screen time per app, the number of
blocks and the number of notifications hidden. You can exclude apps from tracking, which also
deletes their history.

### Reliability

The blocker runs as a foreground service with a persistent notification, so Android doesn't kill
it. It starts again by itself after a reboot. A background check warns you if the Accessibility
service has been turned off. Banners on the home screen point out missing permissions and
battery-optimisation settings that would make blocking unreliable.

---

## How it works

The app doesn't need root. It relies on standard Android features:

- **Accessibility service.** This is the core of the app. It reports which app is in front, reads
  the browser's address bar, detects when the phone is unlocked, and handles ejecting you and
  guarding Settings.
- **Display over other apps.** Used to draw the block page and the countdown screens.
- **Notification listener.** Used for profile notification handling, the notification filters
  and the nothing-new pause.
- **Device admin.** Used only for Strict Mode level 2 and above, to block uninstalling.
- **Location, including in the background.** Used only if a profile has a Location condition.

A small rules engine (`domain/engine/`) makes every block decision. It is plain Kotlin with no
Android code and is covered by JVM unit tests. The services feed it the app in front, the
current URL and live signals (time, location, charging, usage counters), and carry out what it
decides.

Built with Kotlin, Jetpack Compose (Material 3), Hilt, Room, DataStore, WorkManager, Play
Services Location and osmdroid. Requires at least Android 8.0 (API 26) and targets API 35.

---

## Building and installing

You need JDK 17 or newer and the Android SDK, with `ANDROID_HOME` set.

```bash
./gradlew :app:installDebug        # build and install on the connected device
./gradlew :app:testDebugUnitTest   # run the engine unit tests
```

The app is meant to be used as the **debug build**, which installs as `com.customblocker.debug`.
The debug signing key is committed in `keystore/` on purpose. Every machine then builds the same
signature, so an install from one machine can be updated from another without being uninstalled
first.

After installing, open the app and follow the banners to grant permissions. You must at least
enable the Accessibility service and allow display over other apps.

> **Before you reinstall or wipe the app, back up its data:**
> `scripts/localbackup.sh backup` saves the profiles, rules and stats from the device into
> `backups/`, which git ignores. `scripts/localbackup.sh restore <file>` puts them back. If
> Strict Mode level 2 is on, turn it off in the app before you uninstall.

### Dev helpers

`scripts/dev.sh` wraps the emulator, build and logcat loop:

```bash
scripts/dev.sh emu_start && scripts/dev.sh emu_wait
scripts/dev.sh install && scripts/dev.sh run
scripts/dev.sh logcat     # tagged decision logs: BlockEngine / FgDetect / Overlay / Cond
```

Debug builds also include a broadcast receiver that fakes condition inputs (charging, time,
geofence, Wi-Fi), so you can test conditions from the command line. See
`app/src/debug/java/com/customblocker/debug/DebugSignalReceiver.kt`.

---

## Project layout

```
app/src/main/java/com/customblocker/
  domain/engine/   rules engine: profiles, conditions, websites, notification filters (pure Kotlin)
  domain/strict/   Strict Mode lock state and unlock logic
  data/            Room database, DataStore preferences, repository, stats recording
  service/         accessibility service, overlay, notification listener, device admin,
                   condition signals (geofence, time alarms, usage tracking), watchdog
  ui/              Compose screens: profiles, conditions, map picker, notifications,
                   strict mode, settings, stats
app/src/test/      JVM unit tests for the engine and Settings guard
docs/PLAN.md       original design plan (architecture and reasoning)
docs/DEVLOG.md     development log for the early phases
scripts/           dev loop and local backup tooling
```

`docs/` records the original design and the first build phases. It isn't kept up to date. Later
features are described in the git history.

---

## Limitations

- **Pixel / stock Android first.** The Strict Mode Settings guard depends on how Settings is laid
  out, so it may miss screens on other manufacturers' builds. Some Samsung-specific cases are
  handled.
- **It is not tamper-proof.** Anyone with `adb`, root, or the patience to boot into safe mode can
  get around it. The aim is to add friction, not to be impossible to defeat.
- **Website blocking only works in the browsers listed above.** It relies on each browser's
  address-bar layout, which can change when the browser updates.
- Aggressive battery-saving settings can delay location and time-based changes.

## Status

This is a personal project, used daily on my own phone. There's no release build, no store
listing and no promise of support. Feel free to read or borrow from it.
