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

## Pairing the watch (the "auth key")

The CMF Watch Pro 2 only talks to an app that knows its **auth key**: a secret of 32 hex characters (0-9, a-f) shared by the watch and the phone. You cannot choose it. The watch's maker creates it when the watch is paired with the vendor app (CMF Watch / Nothing X), and Gadgetbridge needs the same value. Official help: <https://gadgetbridge.org/basics/pairing/nothing-cmf-server/>.

Ways to get the key, in the order to try:

1. **You still have it** (note, password manager, the old Gadgetbridge export zip). Long-press the watch in *Device discovery* → *Auth key*, paste it without pressing Enter.
2. **Experimental, no key (this fork only):** enter 32 zeros. The fork treats an all-zero key as "no key" and asks the watch to negotiate a new one (the driver's `AT GETSECRET` path). It can only work on a watch that is not bound to another phone/app, so reset the watch first (watch settings → factory reset). If it says "authentication failed", use way 3.
3. **Read it while pairing with the vendor app** (no root needed): reset the watch, install the *CMF Watch* app, make an account, start `adb logcat` on the PC, pair the watch in the vendor app, and read the 32-hex value after `k1 bind:`, `"K1":"` or `sign secret:` (the command is on the Gadgetbridge page above). Do **not** unpair the watch in the vendor app afterwards: that invalidates the key.

Once connected, take a Gadgetbridge export (*Data management → Export*) and keep the zip: it holds the key. A watch bonds with one app at a time, so disconnect it from other apps first.

## Use

Menu → **SleepWake night**.

1. Set the wake time and the max range (0–60 min). Window = target ± range, for example 07:30 ± 15 = 07:15 to 07:45.
2. There is only one mode. It always records the whole night, wakes you at the first light-sleep moment inside the window, and, if there is none, at the end of the window (the "max range"). A setting *Data only: never wake early* turns the early wake off (then it wakes at the target time).
3. Read the checklist (watch connected, battery, notifications, exact alarms, battery optimization, sync). Problems are warned about, never blocking.
4. **Start night session.** A persistent notification shows the state, the next check, the last sync and the connection.
5. In the morning the watch vibrates (find-watch, repeated every 30 s because the CMF driver cannot stop it from the phone; you can also stop it on the watch). If the watch is not connected, the phone vibrates (or plays the alarm sound, setting). Tap **I'm awake** (button in the notification or on the screen). Then rate how you feel (1–5).

Settings: data-only switch, fetch interval (default 5 min), monitor-only wake time, fallback, detector numbers, and **Sync to webapp** (URL, token, interval, Wi-Fi only, Test connection, Sync now).

## Sync to the webapp

1. In the webapp (Settings page) create an API token.
2. In SleepWake settings: enable sync, base URL `https://gadget.peppasoft.com`, paste the token, *Test connection*.
3. Uploads are incremental and safe to repeat (the server dedupes): sessions, events (including every detector evaluation with its inputs), all samples (a 6 h trailing lookback catches late data).

## Manual test checklist

Run these on the real phone and watch. Tick them off and write the date.

- [ ] Fresh install next to the official app (no `INSTALL_FAILED_CONFLICTING_PROVIDER`), import of the Gadgetbridge export, watch connects.
- [ ] Checklist shows the right state for each item (turn Bluetooth off, deny notifications, remove the exact-alarm permission).
- [ ] **Short test**: start with the target 1–2 hours ahead and "Data only: never wake early" on. Samples appear, `fetch_finished` events show `latency_s`, the wake fires at the target time, **I'm awake** stops the repeating, rating screen appears, session is closed.
- [ ] **Normal night**, range 15: wakes only inside the window, and always by the deadline. Check the `detector_evaluated` events (inputs and decision) in the webapp.
- [ ] **Bluetooth drop at the deadline**: turn the watch's Bluetooth off before the deadline. The phone must vibrate (reason `FALLBACK`), and the watch must buzz again when it reconnects.
- [ ] **Service killed mid-night** (force-stop the app, or swipe it away): the deadline alarm still fires; ticks resume (`Tick` alarm restarts the service).
- [ ] **Phone reboot mid-night**: after boot the notification returns and the deadline alarm is back (`SwBootReceiver`).
- [ ] **Doze**: screen off, unplugged, 1+ hour: ticks still arrive (battery optimization exempt), the deadline fires on time.
- [ ] **No network for a night**, then turn it on: everything uploads, no duplicates on the webapp (events and samples counts equal the phone's).
- [ ] Stop session by hand during a night: final fetch, session closed, uploaded.
- [ ] Measure and write down: delay between a movement and the sample being fetchable (`latency_s`), duration of one find-watch vibration. Put both in DISCOVERY.md (section "Measured").

## Detector config from the server

The detector (what counts as "light sleep") is not fixed in the app. At each sync, and right before a night starts, the app downloads the newest *detector config* from the webapp (`GET /api/v1/config`, Settings page of the webapp shows the versions). The config names the detector (`heuristic_v1` or `scored_v1`) and its numbers. With no config the built-in `heuristic_v1` is used. Each night stores the config version it used (`detector_params.config_version`), and every evaluation logs its inputs, so the effect of a change can be checked. How the algorithm is improved from the nights: [../optimize-app.md](../optimize-app.md).

## Day mode, battery, kills

- The module itself does nothing outside a night (no service, no alarm, no network except the sync job every 15 minutes if sync is on). The main battery use is Gadgetbridge's own Bluetooth connection to the watch, as in the official app.
- **Stop service (day mode)** on the Night screen quits the app service and disconnects the watch (it is Gadgetbridge's own *Quit*). It is refused while a night is running. Open the app in the evening: it reconnects, and the checklist tells you if not.
- During a night: the hard alarm (`setAlarmClock`) fires even if Android kills the process, and the tick alarms restart the service. **Force stop** (Settings → Apps → Force stop) cancels all alarms of the app until it is opened again, so do not do that during a night. On Samsung, also set the app to *Unrestricted* battery and add it to *Never sleeping apps*.
- After a reboot the app starts by itself (Gadgetbridge's "start on boot", default on) and a running night is restored by `SwBootReceiver`.
