# SleepWake (Android) — setup, use, tests

Fork of Gadgetbridge for the CMF Watch Pro 2. Details of what changed: [SLEEPWAKE_CHANGES.md](SLEEPWAKE_CHANGES.md). What the watch and the code really do: [../DISCOVERY.md](../DISCOVERY.md). Spec: [../reference-docs/SLEEPWAKE_PROJECT_SPEC.md](../reference-docs/SLEEPWAKE_PROJECT_SPEC.md).

## Build

Needs JDK 21 (Android Studio's `jbr` is fine) and the Android SDK (Gradle downloads platform 37 on first build).

```bash
cd android
printf 'sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk\n' > local.properties   # forward slashes
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleSleepwakeDebug            # APK in app/build/outputs/apk/sleepwake/debug/
./gradlew testSleepwakeDebugUnitTest        # unit tests of the module
adb install -r app/build/outputs/apk/sleepwake/debug/*.apk
```

The debug APK accepts `http://` for the sync URL (for local tests). The release type accepts https only.

## Move the watch to the fork (once)

A watch bonds with one app at a time, and the CMF watch needs the auth key that the official app stored.

1. In the **official** Gadgetbridge: *Data management → Export* (the zip holds the device list, auth key and settings).
2. In **SleepWake**: *Data management → Import* that zip.
3. Disconnect the watch in the official app (or turn off its Bluetooth connection), then connect in SleepWake.

## Use

Menu → **SleepWake night**.

1. Set the wake time and the tolerance (0–60 min). Window = target ± tolerance.
2. Choose the mode:
   - **Monitor only**: records everything, one wake at the target time (or at the deadline, setting).
   - **Smart wake**: wakes at the first light-sleep moment inside the window; at the deadline it always wakes you.
3. Read the checklist (watch connected, battery, notifications, exact alarms, battery optimization, sync). Problems are warned about, never blocking.
4. **Start night session.** A persistent notification shows the state, the next check, the last sync and the connection.
5. In the morning the watch vibrates (find-watch, repeated every 30 s because the CMF driver cannot stop it from the phone; you can also stop it on the watch). If the watch is not connected, the phone vibrates (or plays the alarm sound, setting). Tap **I'm awake** (button in the notification or on the screen). Then rate how you feel (1–5).

Settings: fetch interval (default 5 min), monitor-only wake time, fallback, detector numbers, and **Sync to webapp** (URL, token, interval, Wi-Fi only, Test connection, Sync now).

## Sync to the webapp

1. In the webapp (Settings page) create an API token.
2. In SleepWake settings: enable sync, base URL `https://gadget.peppasoft.com`, paste the token, *Test connection*.
3. Uploads are incremental and safe to repeat (the server dedupes): sessions, events (including every detector evaluation with its inputs), all samples (a 6 h trailing lookback catches late data).

## Manual test checklist

Run these on the real phone and watch. Tick them off and write the date.

- [ ] Fresh install next to the official app (no `INSTALL_FAILED_CONFLICTING_PROVIDER`), import of the Gadgetbridge export, watch connects.
- [ ] Checklist shows the right state for each item (turn Bluetooth off, deny notifications, remove the exact-alarm permission).
- [ ] **Monitor-only night**: start at bedtime, target 1–2 hours ahead for a short test. Samples appear, `fetch_finished` events show `latency_s`, the wake fires at the target time, **I'm awake** stops the repeating, rating screen appears, session is closed.
- [ ] **Smart-wake night**, tolerance 15: wakes only inside the window, and always by the deadline. Check the `detector_evaluated` events (inputs and decision) in the webapp.
- [ ] **Bluetooth drop at the deadline**: turn the watch's Bluetooth off before the deadline. The phone must vibrate (reason `FALLBACK`), and the watch must buzz again when it reconnects.
- [ ] **Service killed mid-night** (force-stop the app, or swipe it away): the deadline alarm still fires; ticks resume (`Tick` alarm restarts the service).
- [ ] **Phone reboot mid-night**: after boot the notification returns and the deadline alarm is back (`SwBootReceiver`).
- [ ] **Doze**: screen off, unplugged, 1+ hour: ticks still arrive (battery optimization exempt), the deadline fires on time.
- [ ] **No network for a night**, then turn it on: everything uploads, no duplicates on the webapp (events and samples counts equal the phone's).
- [ ] Stop session by hand during a night: final fetch, session closed, uploaded.
- [ ] Measure and write down: delay between a movement and the sample being fetchable (`latency_s`), duration of one find-watch vibration. Put both in DISCOVERY.md (section "Measured").
