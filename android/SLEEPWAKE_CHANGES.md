# SLEEPWAKE_CHANGES.md

This folder is a copy of upstream [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (AGPL-3.0-or-later, licence and headers unchanged) with the SleepWake module added.

- **Upstream base:** commit `efb0ae5c41d3b3f3903b61959dc7ca908bc77828` (2026-10-19, version 0.95.1, versionCode 255).
- To update: clone upstream at the new commit, copy it over `android/` **except** the files listed below as "added", then re-apply the four small edits of "touched upstream files" (each is marked with a `SLEEPWAKE FORK` comment).

## Design rule

All new code is in a **build flavor** `sleepwake` (`app/src/sleepwake/`, package `nodomain.freeyourgadget.gadgetbridge.sleepwake`). The upstream flavors (`mainline`, `banglejs`) do not contain it and build exactly as before, so the no-network upstream build still exists.

## Added (new files only)

| Path | What |
|---|---|
| `app/src/sleepwake/java/.../sleepwake/core/` | pure Kotlin: `WakeWindow`, `WakeEngine`, `WakeDetector` + `NoopDetector` + `HeuristicDetector`, `SyncPlan`, `Json`, `Simulation` (no Android classes, unit tested) |
| `.../sleepwake/data/` | `SleepWakeStore` (own SQLite file `sleepwake.db`), `SwSettings` (prefs + encrypted token) |
| `.../sleepwake/service/` | `NightSessionService`, `NightSessionManager`, `WakeActuator`, `SwAlarms`, `SwAlarmReceiver`/`SwBootReceiver`, `Notifier`, `DeviceAccess`, `SampleReader` |
| `.../sleepwake/sync/` | `ServerSync` (HTTPS client), `SyncScheduler` + `SyncWorker` (WorkManager) |
| `.../sleepwake/ui/` | `NightActivity`, `SleepWakeSettingsActivity`, `MorningRatingActivity` |
| `app/src/sleepwake/AndroidManifest.xml` | INTERNET, ACCESS_NETWORK_STATE, USE_FULL_SCREEN_INTENT, the module's activities, service and receivers |
| `app/src/sleepwake/res/` | `app_name` "SleepWake", own `pebble_content_provider` authority, preference screen, arrays |
| `app/src/testSleepwake/` | unit tests (window/deadline/DST, modes, detector, simulation, sync batching, JSON) |
| `SLEEPWAKE_CHANGES.md`, `SLEEPWAKE.md` | these notes, setup and the manual test checklist |

## Touched upstream files (4)

1. `app/build.gradle`
   - new product flavor `sleepwake` (`applicationIdSuffix ".sleepwake"`, `INTERNET_ACCESS = true`)
   - dependency `sleepwakeImplementation "androidx.security:security-crypto"` (encrypted token storage, only in this flavor)
   - `buildGitChangelog`: looks for `.git` one level up, because this project lives in `android/` of a larger repo
2. `app/src/main/res/menu/activity_main_drawer.xml`: one hidden menu item `action_sleepwake`.
3. `app/src/main/res/values/strings.xml`: one string `sw_menu_sleepwake`.
4. `app/src/main/java/.../activities/ControlCenterv2.java`: shows the menu item only when the `NightActivity` class exists in the build, and opens it (by class name, no compile dependency), plus one import.

No other upstream file is edited. No upstream class is subclassed or replaced. The module uses only public entry points: `GBApplication.deviceService(...)`, `GBApplication.acquireDbReadOnly()`, the device's sample provider, and the `ACTION_NEW_DATA` / `ACTION_DEVICE_CHANGED` local broadcasts.

## Network (conscious change)

Upstream deliberately has no `INTERNET` permission. In this fork:

- the permission exists **only in the `sleepwake` flavor**; `mainline` and `banglejs` are unchanged
- all networking is in `sync/ServerSync.kt`: it talks **only** to the base URL the user typed, **https only** (http is accepted in debug builds only), bearer token, no analytics, no third-party SDK, no other host
- the token is stored in `EncryptedSharedPreferences` (Android Keystore) and is never logged
- the night loop never touches the network; uploads run in WorkManager (network constraint, exponential backoff, optional "Wi-Fi only")
- a build with `BuildConfig.INTERNET_ACCESS == false` refuses to sync

## Other differences from the official app

- Different application id (`nodomain.freeyourgadget.gadgetbridge.sleepwake`): separate data, separate paired-device list, installs next to the official app. A watch bonds with one app at a time.
- Own `pebble_content_provider` authority (`com.getpebble.android.sleepwake.provider`), otherwise the two apps cannot be installed together.
- The module's own DB is `sleepwake.db`; Gadgetbridge's greenDAO DB is only read, never changed.
