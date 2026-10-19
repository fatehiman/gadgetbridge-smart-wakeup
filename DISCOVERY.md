# DISCOVERY (Phase 0)

Result of reading the Gadgetbridge source (upstream base `efb0ae5c41d3b3f3903b61959dc7ca908bc77828`, version 0.95.1, 2026-10-19).
All paths are relative to `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/` unless they start with `GBDaoGenerator/`.
Nothing here is a guess: every item was read in the code. Items that can only be known from a real night are marked **OPEN (needs the watch)**.

## 1. CMF Watch Pro 2 support code

- Coordinator: `devices/cmfwatchpro/CmfWatchPro2Coordinator.java` (extends `CmfWatchProCoordinator`). Name regex `^CMF Watch Pro 2(-[A-Z0-9]{4})$` (so `-3BAD` matches). Registered in `model/DeviceType.java:1050`.
- Device support: `service/devices/cmfwatchpro/CmfWatchProSupport.java`. Helpers in the same package: `CmfCharacteristic` (framing, AES, CRC), `CmfCommand` (command enum), `CmfActivitySync` (fetch + persistence), `CmfPreferences` (settings).
- Auth is required (`requiresAuthKey()`): a 32-character key. **A fresh install of the fork does not know the key of the official app.** Move the paired device over with the Gadgetbridge export/import (Data management).
- Commands used by this project:
  - fetch: `ACTIVITY_FETCH_1` (ffff/8005) payload `A5`, ack `ACTIVITY_FETCH_ACK_1`
  - find watch: `FIND_WATCH` (005d/0001), empty payload
  - heart monitoring: `HEART_MONITORING_ENABLED_SET` (009b/0001), sub-code 1 = smart HR, 2 = SpO2, 4 = stress
  - alarms: `ALARMS_SET` (0063/0001)

## 2. Data stored (greenDAO schema 157)

Source: `GBDaoGenerator/src/nodomain/freeyourgadget/gadgetbridge/daogen/GBDaoGenerator.java:816-871`.

| Entity (table = snake case of entity) | Key | Values |
|---|---|---|
| `CmfActivitySample` | `TIMESTAMP` (int, **seconds**) + `DEVICE_ID` | `RAW_INTENSITY`, `STEPS` (cumulative on the wire, converted by the provider), `RAW_KIND`, `HEART_RATE`, nullable `DISTANCE`, `CALORIES` |
| `CmfHeartRateSample` | `TIMESTAMP` (long, **ms**) + `DEVICE_ID` | `HEART_RATE` |
| `CmfSpo2Sample` | same | `SPO2` |
| `CmfStressSample` | same | `STRESS` |
| `CmfSleepSessionSample` | same | `WAKEUP_TIME` (ms), `METADATA` (10 bytes) |
| `CmfSleepStageSample` | same | `DURATION` (s), `STAGE` (1 deep, 2 light, 3 REM) |

- The sample provider `devices/cmfwatchpro/samples/CmfActivitySampleProvider.java` overlays HR and sleep stage on the activity samples. Reading through the provider (`getAllActivitySamples`) therefore gives, per timestamp: HR, steps, kind (light/deep/REM/awake) and intensity.
- `RAW_INTENSITY` seems never written by the CMF driver, so it is probably always 0. **Movement for the detector = steps delta** (the provider already converts the cumulative counter). HR-only use is the fallback if steps are empty at night. **OPEN (needs the watch)**: whether steps are non-zero when only turning in bed.
- Rows are `insertOrReplace`d, so re-reading and re-uploading is idempotent.
- No auto-increment row id: incremental reads use a high-water timestamp plus a trailing lookback (6 h, `SwSettings.SAMPLE_LOOKBACK_SECONDS`).

## 3. How a fetch is triggered and finished

- Trigger (same path as the refresh button, `activities/charts/ActivityChartsActivity.java:510-517`):
  `GBApplication.deviceService(device).onFetchRecordedData(RecordedDataTypes.TYPE_...)` after checking `device.isInitialized()`.
  The CMF driver **ignores the data type mask** (`CmfWatchProSupport.java:784`): every fetch pulls everything new.
- Flow (`CmfActivitySync`): watch acks step 1 (`0x01`) -> app sets busy task and requests step 2 -> watch pushes activity, HR, SpO2, stress, sleep, workouts -> watch sends ack `0x02` -> `processActivitiesWithGps()` -> `GB.signalActivityDataFinish(device)`.
- Completion signal: `LocalBroadcastManager` action `GBApplication.ACTION_NEW_DATA` (`...gadgetbridge.action.new_data`), extra `GBDevice.EXTRA_DEVICE`. Also `device.isBusy()` goes false. **There is no timeout or error path in the driver** if the watch never sends `0x02`, so the module has its own timeout (150 s).
- The broadcast can fire more than once per fetch (upstream debounces it for 10 s). The module waits 2 s after the signal, then reads.

## 4. Find watch

- `onFindDevice(boolean start)` (`CmfWatchProSupport.java:804-810`) returns at once when `start == false`: **the vibration cannot be stopped from the phone**. Start sends `FIND_WATCH` with an empty payload. Duration and pattern are chosen by the watch. **OPEN (needs the watch)**: how long one find-watch vibrates.
- Consequence (implemented): `WakeActuator` repeats find-watch every 30 s until "I'm awake", and escalates to a phone alert after 6 unanswered attempts. The user can also stop it on the watch.

## 5. Alarms

- The coordinator offers 5 alarm slots (`getAlarmSlotCount`), no smart-wake or snooze. `onSetAlarms` writes 40-byte records (time of day, slot, enabled, repetition, title). Nothing in the code says whether the alarm only vibrates.
- **Decision**: the optional "backup alarm on the watch" from the spec is **not implemented**, because "vibrates only" is not verified. The phone-side hard alarm (`setAlarmClock`) is the guarantee. It can be added later after a test with the real watch.

## 6. Live / push data

- None. `onEnableRealtimeSteps`, `onEnableRealtimeHeartRateMeasurement`, `onHeartRateTest` are not implemented for this device, and `supportsManualHeartRateMeasurement()` is false. Data only arrives by fetch. The whole design is therefore a fetch loop.

## 7. Device settings

- HR monitoring interval choices for this device: only OFF and SMART (`CmfWatchProCoordinator.getHeartRateMeasurementIntervals`, lines 373-378). **The "shortest interval" is therefore SMART**; the actual sampling rate is decided by the watch. `onSetHeartRateMeasurementInterval(-1)` sends `HEART_MONITORING_ENABLED_SET {01, 01}`.
- SpO2 all-day monitoring: pref `spo2_all_day_monitoring_enabled` -> `{02, on}`. Stress monitoring: `heartrate_stress_monitoring` -> `{04, on}`.
- Sleep tracking has no setting for this device (the screen hides it): the watch records sleep itself.
- At session start the module sets HR (smart) and SpO2 all-day, and records what it applied in the `session_started` event (`device_config`).

## 8. Connection state

- `GBDevice.State`: `NOT_CONNECTED, WAITING_FOR_RECONNECT, WAITING_FOR_SCAN, SCANNED, CONNECTING, CONNECTED, INITIALIZING, AUTHENTICATION_REQUIRED, AUTHENTICATING, INITIALIZED`. Only `INITIALIZED` (`device.isInitialized()`) is safe for a fetch.
- Changes are broadcast with `GBDevice.ACTION_DEVICE_CHANGED` (LocalBroadcastManager). `GBApplication.deviceService(device).connect()` reconnects. Upstream has per-device auto-reconnect (default on) with a retry delay in `service/btle/BtLEQueue.java`. The module adds a watchdog (30 s, 60 s ... 5 min backoff) and logs `connection_lost` / `connection_restored`.

## 9. Android constraints

- Upstream manifest has **no INTERNET permission** (explicitly removed). The fork's `sleepwake` flavor adds INTERNET + ACCESS_NETWORK_STATE in `app/src/sleepwake/AndroidManifest.xml`; the unchanged `mainline` flavor stays without network.
- Foreground service type for the night service: `connectedDevice` (same as upstream `DeviceCommunicationService`; needs the Bluetooth permissions the app already holds).
- Already declared upstream: `SCHEDULE_EXACT_ALARM`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `VIBRATE`. The fork adds `USE_FULL_SCREEN_INTENT`.
- The hard alarm uses `AlarmManager.setAlarmClock` (needs no special permission, Doze-proof). Ticks and repeats use `setExactAndAllowWhileIdle` when the exact-alarm permission is granted (the Night screen checks it), else `setAndAllowWhileIdle`.
- A different `applicationId` (`...gadgetbridge.sleepwake`) installs next to the official app, with its own database and paired devices. The Pebble content provider authority must differ (otherwise `INSTALL_FAILED_CONFLICTING_PROVIDER`), so the flavor overrides `pebble_content_provider`.
- **A watch can bond with only one app at a time.** Disconnect the official Gadgetbridge before using the fork.

## 10. Latency experiment (3.9)

The module logs it in every night, with no extra debug patch:
- `fetch_started` / `fetch_finished` events carry `took_ms`, `samples`, `max_sample_ts` and `latency_s` (receive time minus the newest sample time).
- The Night screen shows, for the last night, the number of fetches and the median and max latency.
- The webapp shows the same events on the night page (event table) and in the export.

**OPEN (needs the watch)**: the real delay distribution, and whether data can be pulled repeatedly while the watch is worn. Until measured, the default fetch interval stays at **5 minutes** (setting, minimum 1). Run one monitor-only night, then read `latency_s` in the export and adjust.

## Build notes

- Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4, JDK 21 (Android Studio's bundled JBR), compileSdk 37 (Gradle downloads it). minSdk 23, targetSdk 35.
- Command: `./gradlew assembleSleepwakeDebug` (see `android/SLEEPWAKE.md`).
