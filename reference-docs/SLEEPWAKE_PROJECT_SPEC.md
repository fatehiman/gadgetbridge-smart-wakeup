# SleepWake: Project Specification

Two deliverables, built from this one document:

1. **Android app**: a fork of [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) that records my sleep data from a CMF Watch Pro 2, wakes me with watch vibration at a light-sleep moment inside a time window, and syncs everything to my server.
2. **Webapp**: a small Laravel + Blade + MySQL app on my own server that receives the data over an API and shows it as graphs.

Single user (me). Personal use only. No multi-tenant features are needed.

> **How to work:** Start with **Phase 0 (Discovery)** below and write `DISCOVERY.md` before implementing anything. Several details depend on Gadgetbridge internals and on what the watch really sends. Do not guess class names, intent actions, or DB columns. Read the code and verify.

---

## 1. Context and goals

### Hardware
- **CMF Watch Pro 2** (model 3BAD), supported by Gadgetbridge. Pairing works without the vendor app.
- Nothing can run on the watch itself (closed RTOS, no SDK). The watch is only a sensor and a vibration motor. **All logic runs on the phone.**
- I wear the watch **only at night**. It is off or unused in the daytime, so **battery life is irrelevant**. Use the most aggressive sensor and sync settings (shortest HR interval, frequent fetching).

### Already verified manually
- Pressing refresh/fetch in Gadgetbridge returns lots of continuous data.
- "Find watch" from Gadgetbridge makes the watch vibrate.

### Unknowns to resolve in Phase 0
- Exactly which fields each sample contains (HR, steps, movement/intensity, SpO2, sleep stage, etc.).
- The latency between a physical event and the sample becoming fetchable.
- Whether the find-watch vibration can be stopped programmatically, and its duration and pattern.
- Whether the watch's own alarm can be set a minute or two ahead as a backup, and whether it vibrates only.

### Goals
1. **Monitor-only nights.** Record all available data every night, forever. I will analyze it later (monthly, with AI) to design a light-sleep detector.
2. **Smart wake.** I set a target time and a tolerance (example: 08:00 ± 15 min). The app wakes me by **vibrating the watch** at the first moment inside the window when a "light sleep" condition is detected. If none is detected, it wakes me at the end of the window (08:15 in the example). **The deadline is guaranteed.**
3. **Sync to webapp.** All data goes to my server through a token-authenticated API, and I see it as graphs.

### Non-goals
- No on-watch app. No custom watch firmware.
- No medical-grade sleep staging. "Light sleep" is a heuristic on wrist data. REM and light sleep cannot be reliably separated from a wrist sensor. The goal is "not in deep sleep".
- No iOS. No public distribution. No multi-user support.
- No daytime use.

---

## 2. Architecture

```
 CMF Watch Pro 2
   │  BLE (existing Gadgetbridge protocol code)
   ▼
 Gadgetbridge fork (Android)
   ├─ existing: device connection, fetch, activity DB
   └─ NEW module "sleepwake":
        ├─ NightSessionService (foreground service)
        ├─ SyncScheduler (periodic fetch while session active)
        ├─ WakeEngine (window logic + pluggable detector)
        ├─ WakeActuator (find-watch vibration, backups)
        ├─ SleepWake DB (own SQLite file: sessions, events)
        ├─ UI: Night screen + settings
        └─ ServerSync (WorkManager, HTTPS, token)
   │  HTTPS JSON, Bearer token
   ▼
 Laravel webapp (PHP 8.4, MySQL, Blade)
   ├─ API: ingest
   └─ UI: nights list, night detail graphs, trends, export
```

---

## 3. Part A: Android app (Gadgetbridge fork)

### 3.1 Repository and fork rules
- Fork from Codeberg, work on a long-lived branch (for example `sleepwake`), and tag the upstream base commit.
- **Keep upstream changes minimal and isolated.** Put all new code in a new package (for example `nodomain.freeyourgadget.gadgetbridge.sleepwake`). Touch existing files only for unavoidable hooks (menu entry, settings entry, manifest). The goal is that I can rebase onto upstream releases without pain. Document every touched upstream file in `SLEEPWAKE_CHANGES.md`.
- Use a **different `applicationId`** (for example suffix `.sleepwake`) so the fork installs separately from the official app and can't be overwritten by it. Note that a watch can only bond to one phone/app at a time.
- License is AGPL-3.0. Keep headers and license intact.
- Match upstream code style and language (Java/Kotlin mix). Prefer Kotlin for new files only if upstream already accepts it. Check first.

### 3.2 Phase 0: Discovery (do first, output `DISCOVERY.md`)
Investigate and document, with file paths:
1. The CMF/Nothing watch support code: coordinator, device support, sample provider, which BLE commands exist.
2. The DB schema (greenDAO entities) used for CMF activity, HR and sleep samples: table names, columns, timestamp units.
3. How an activity fetch is triggered programmatically (the same code path the refresh button uses), how completion is signaled, and how to listen for it.
4. How "find watch" is implemented, how to start and stop it from code, and its vibration behavior.
5. Whether Gadgetbridge supports setting an alarm on this device, and its behavior.
6. Whether any live (push) data path exists for this device, such as live activity or real-time HR.
7. The device settings available (continuous HR, interval, sleep tracking) and how to set them programmatically.
8. How connection state is exposed, and how reconnection works after a drop.
9. Android constraints: foreground service types, permissions needed, and battery optimization exemption.
10. A latency experiment plan (see 3.9) with a tiny debug logging patch.

Do not start Phase 1 until `DISCOVERY.md` exists and I have reviewed it.

### 3.3 Night session
A "session" is one night of monitoring, started and stopped by me (or by a schedule, optional).

- **Night screen** (new activity, reachable from the main menu):
  - Target wake time (time picker).
  - Tolerance in minutes (default 15, range 0 to 60). Window = `[target − tol, target + tol]`.
  - Mode: **Monitor only** or **Smart wake** (see 3.5).
  - Start/Stop session button.
  - Pre-sleep checklist shown before start: watch connected, continuous HR enabled, watch battery, notifications and exact-alarm permission OK, server sync status. Warn clearly on failures. Do not block me.
- On start: ensure the watch is connected, apply the required device settings (continuous HR at the shortest interval, sleep tracking on), start the foreground service, schedule the hard-deadline alarm (3.6), and create a session row.
- On stop (manual, or automatically after wake is acknowledged): stop the service, cancel pending alarms, do a final fetch, and mark the session closed.
- The persistent notification must show session state, next planned action, last sync time, and connection state.

### 3.4 Night monitoring loop (`NightSessionService`)
- Foreground service (use the correct service type for Android 14+).
- Every **N minutes** (default 5, configurable, minimum 1), run an activity fetch, wait for completion, and read new samples.
- Connection watchdog: if disconnected, attempt reconnect with backoff, log events, and keep the deadline alarm independent of the connection.
- Record **everything** the watch gives (see 3.7). Never drop fields because they look unused.
- Handle Doze: use a partial wake lock only as needed, `setExactAndAllowWhileIdle` for deadline alarms, and make sure the user enables the battery optimization exemption.
- Everything must survive the service being killed. State lives in the DB, not in memory, and the service restores on restart.

### 3.5 Wake engine

**Window and deadline (this is the part that must be bulletproof):**
- `window_start = target − tol`, `deadline = target + tol`.
- Before `window_start`: never wake.
- Inside the window: ask the detector on every evaluation. If it says wake, trigger.
- At `deadline`: **always trigger**, regardless of detector or connection state.

**Modes:**
- **Monitor only:** record everything, the detector is never consulted for waking. A single wake fires at the **target time** (setting: target or deadline, default target). This is for collecting data and for ordinary alarm nights.
- **Smart wake:** detector-driven within the window, deadline guaranteed.

**Detector is pluggable:**
```kotlin
interface WakeDetector {
    fun evaluate(ctx: DetectionContext): Decision
}
data class DetectionContext(
    val now: Instant,
    val windowStart: Instant,
    val deadline: Instant,
    val sessionStart: Instant,
    val samples: List<Sample>   // all session samples so far, time-ordered
)
sealed interface Decision {
    object Wait : Decision
    data class WakeNow(val reason: String, val confidence: Double?) : Decision
}
```
Implementations to ship:
1. `NoopDetector`: always `Wait` (used by monitor-only).
2. `HeuristicDetector` v1 (placeholder to be replaced after I analyze data). Parameters configurable in settings, with the defaults below:
   - `mov_recent` = movement in the last 5 min.
   - `hr_recent` = median HR in the last 5 min.
   - `hr_base` = 20th percentile of HR since estimated sleep onset.
   - Light sleep if `mov_recent > 0` **or** `hr_recent > hr_base + 8 bpm`.
   - Use whatever movement/intensity field Phase 0 finds. If no usable movement field exists, use HR only and document the limitation.

Make the detector easy to replace: later I will derive a better algorithm from months of data. Log the **inputs and outputs of every evaluation** (see 3.7) so the decision can be replayed offline.

Keep all time logic in a pure, unit-testable class with an injectable clock.

### 3.6 Wake actuator and backups
- Primary: trigger the watch's **find-watch vibration**. Repeat or escalate if it stops before I acknowledge. Details come from Phase 0.
- **Acknowledgement:** I need a way to say "I'm awake". Options to evaluate in Phase 0: a button in the notification, a button on the Night screen, or detecting significant movement. Once acknowledged, stop vibration and close the session.
- **Independent hard-deadline alarm:** an `AlarmManager.setExactAndAllowWhileIdle()` (or `setAlarmClock`) alarm at `deadline`, handled by a receiver that does not depend on the service being alive. It tries the watch vibration. If the watch is not connected, it falls back according to the **setting "fallback when watch unavailable"**: phone vibration + notification (default) or phone alarm sound.
- Optional backup (only if Phase 0 shows it works and vibrates without sound): also set a normal alarm on the watch at `deadline`.
- Request exact-alarm permission properly and tell me if it is missing.
- Log a `wake_triggered` event with the reason: `LIGHT_SLEEP`, `DEADLINE`, `MONITOR_TARGET`, `MANUAL`, or `FALLBACK`.

### 3.7 Local data storage (own DB)
Use a **separate SQLite file** (own `SQLiteOpenHelper` or Room if cheap to add), not Gadgetbridge's greenDAO DB. This avoids upstream migration conflicts.

Tables (minimum):
- `sw_session`: `uuid` (PK), `started_at`, `ended_at`, `mode`, `target_time`, `tolerance_min`, `window_start`, `deadline`, `detector_id`, `detector_params_json`, `timezone`, `device_address`, `uploaded` (flag or version).
- `sw_event`: `id`, `session_uuid`, `ts` (epoch ms), `type`, `payload_json`, `uploaded`.
  - Event types: `session_started`, `session_stopped`, `fetch_started`, `fetch_finished`, `fetch_failed`, `connection_lost`, `connection_restored`, `detector_evaluated` (with inputs summary and decision), `wake_triggered`, `wake_acknowledged`, `battery`, `morning_rating`.
- Samples remain in Gadgetbridge's own tables. The sleepwake module reads them from there and does not duplicate them locally. The **server sync** (3.8) reads them from there too.

Optional but valuable: a **morning rating prompt** (1 to 5 "how do I feel", plus an optional note) shown after wake acknowledgement, saved as an event. This gives ground truth for later analysis.

If the watch reports its own sleep stages or sleep summary, store them too and sync them. Even if I don't trust them, they are useful for comparison with my detector.

### 3.8 Server sync
New settings section **"Sync to webapp"**:
- Enable toggle, base URL (HTTPS), API token, sync interval, "sync only on Wi-Fi" toggle, "Sync now" button, and last sync status/error.
- Store the token with `EncryptedSharedPreferences` (or the Keystore).

**Permission note:** upstream Gadgetbridge deliberately has no `INTERNET` permission. Adding it is a conscious change. Confine all networking to the sync module, talk only to the user-configured URL, reject cleartext HTTP (except debug builds), send no analytics, and document this in `SLEEPWAKE_CHANGES.md`. Consider a **build flavor** `sync` vs `nosync` so the no-network build remains possible.

**Behavior:**
- Use WorkManager (periodic + one-shot after a session ends), network constraint, exponential backoff.
- **Idempotent, incremental uploads** in batches (for example 1000 samples per request, gzip if cheap).
- Samples may arrive late or be back-filled with older timestamps. Track what was uploaded using row ids if available, otherwise timestamp plus a trailing lookback (for example 6 hours). The server dedupes, so re-sending is safe.
- Data synced: device info, sessions, all samples (every field), events (including detector evaluations and wake events), sleep stages or summaries from the watch if present, and morning ratings.
- Never block or delay the night loop on network problems.

### 3.9 Phase 0 experiment: latency measurement
Add a debug-only logger that records, for each fetch, the receive time and the max sample timestamp. Output the delay distribution and note whether data arrives only after manual refresh or can be pulled repeatedly while the watch is worn. This decides whether the 5-minute loop is feasible. Report results in `DISCOVERY.md` with a recommended default interval.

### 3.10 Testing (Android)
- Unit tests: window and deadline logic (before window, inside, at deadline, DST change, crossing midnight), mode behavior, heuristic detector on synthetic data, sync batching and idempotency, event serialization.
- A **simulation harness**: feed recorded or synthetic sample streams plus a fake clock to the engine and print decisions. This is how I will tune detectors later.
- Manual test checklist in the repo: full night run, Bluetooth drop at deadline, service killed mid-night, phone reboot, no network for a night then sync catch-up.

### 3.11 Acceptance criteria (Android)
- [ ] Session start/stop works, with a checklist and a persistent notification.
- [ ] Monitor-only night records samples and events and fires the wake at the configured time.
- [ ] Smart-wake night wakes early only inside the window and **always** by the deadline, even if the watch is disconnected or the service was killed.
- [ ] Vibration stops on acknowledgement.
- [ ] Detector is swappable, and every evaluation is logged with its inputs.
- [ ] All samples and events upload to the server, resumably, with no duplicates after retries.
- [ ] Upstream changes are minimal and documented.

---

## 4. Part B: Webapp (Laravel)

### 4.1 Stack
- PHP 8.4, MySQL, **Laravel (latest stable that supports PHP 8.4) + Blade**, Vite for assets.
- Charts: **Chart.js** (installed through npm and bundled by Vite) with a time-axis adapter, or ECharts if you judge it better. Stick to one library.
- Auth for the UI: single-owner login (Laravel Breeze Blade stack or equivalent). Registration disabled after the first user.
- Auth for the API: **Laravel Sanctum personal access token** with an `ingest` ability, or a hashed token table. Provide an artisan command (and a settings page) to create, show once, and revoke the token.
- Pest or PHPUnit feature tests.

### 4.2 Data model
Store timestamps in UTC. Keep the session's IANA timezone and display in the user's timezone.

- `devices`: `id`, `external_id` (unique, from the app, for example MAC hash), `name`, `model`, `firmware`, `last_seen_at`.
- `sleep_sessions`: `id`, `uuid` (unique, from the client), `device_id`, `started_at`, `ended_at`, `mode`, `target_time`, `tolerance_min`, `window_start`, `deadline`, `detector_id`, `detector_params` (JSON), `timezone`, `wake_reason` (nullable), `morning_rating` (nullable), `notes` (nullable).
- `samples`: `id`, `device_id`, `session_id` (nullable, since samples can also exist outside sessions), `ts` (epoch seconds, indexed), `heart_rate` (nullable), `steps` (nullable), `intensity` (nullable), `movement` (nullable), `spo2` (nullable), `sleep_stage` (nullable, watch-reported), `extra` (JSON, for any other field). **Unique `(device_id, ts)`.** If Phase 0 shows multiple sample kinds at the same timestamp, add `kind` to the unique key instead.
- `events`: `id`, `session_id`, `device_id`, `ts` (epoch ms), `type`, `payload` (JSON), unique `(session_id, ts, type)` for idempotency.
- Add indexes for range queries by `(device_id, ts)`.

Shape the columns from the real sample fields found in the Android Phase 0. The `extra` JSON column ensures nothing is lost.

### 4.3 API (v1, Bearer token, JSON, HTTPS)

`GET /api/v1/ping`: returns `{ "ok": true, "server_time": "..." }`. Used by the app to test URL and token.

`POST /api/v1/ingest`: one idempotent batch endpoint. Upserts by natural keys and returns counts.

Request:
```json
{
  "device": { "external_id": "abc123", "name": "CMF Watch Pro 2", "model": "3BAD", "firmware": "x.y.z" },
  "sessions": [
    {
      "uuid": "0b6f...-...",
      "started_at": 1760850000, "ended_at": 1760879000,
      "mode": "smart_wake",
      "target_time": 1760878800, "tolerance_min": 15,
      "window_start": 1760877900, "deadline": 1760879700,
      "detector_id": "heuristic_v1",
      "detector_params": { "hr_delta": 8 },
      "timezone": "Europe/Helsinki",
      "wake_reason": "LIGHT_SLEEP"
    }
  ],
  "samples": [
    { "ts": 1760850060, "heart_rate": 58, "steps": 0, "intensity": 3, "extra": {} }
  ],
  "events": [
    { "session_uuid": "0b6f...", "ts": 1760878200123, "type": "detector_evaluated",
      "payload": { "decision": "wait", "hr_recent": 55, "mov_recent": 0 } }
  ]
}
```

Response:
```json
{ "ok": true, "accepted": { "sessions": 1, "samples": 1, "events": 1 }, "duplicates": 0 }
```

Rules: validate strictly (size limits such as ≤ 5000 samples per request, sane timestamp ranges), run in a DB transaction, use upserts (`upsert()` or `INSERT ... ON DUPLICATE KEY UPDATE`), rate-limit, return clear 4xx errors, and log failures. Associate samples with a session when `ts` falls inside a session's `[started_at, ended_at]` (or compute this at read time, whichever is simpler and robust to late data).

### 4.4 Pages (Blade)
1. **Dashboard / Nights list:** one row per night with date, mode, target time, wake time and reason, duration, avg/min HR, and morning rating. Filter by date range.
2. **Night detail:** the main page.
   - Time-axis chart: heart rate and movement/intensity (two axes or stacked panels).
   - Vertical markers or bands: session start, **wake window** (shaded), detector decisions, wake trigger, acknowledgement. Show watch-reported sleep stages as a band if present.
   - Table of events with expandable JSON payloads.
   - Editable notes and rating.
3. **Trends:** over a selectable period (week, month, custom): wake time vs target, wake reason distribution (light-sleep vs deadline), average HR, and sleep duration.
4. **Data export** (important, for my monthly AI analysis): download by date range as **CSV** and **JSON** (samples, events, sessions). Include a "one file per night" option or a single combined file, with documented columns.
5. **Settings:** timezone, API token management (create, revoke), and last sync info per device.

Keep the UI clean and simple. No heavy frontend framework.

### 4.5 Quality and deployment
- Seeders/factories to generate realistic fake nights for development.
- Feature tests: auth required, ingest idempotency (same batch twice gives no duplicates), validation errors, late/backfilled samples, export formats.
- README with setup, `.env` keys, migration, token creation, and a note on running behind HTTPS (nginx or Apache). No queue worker should be required.
- Performance: a full night at 1-minute resolution is small, but a year of data should still load quickly. Downsample for long-range charts on the server.

---

## 5. Security and privacy
- Health data. Everything goes only to my own server, over HTTPS, with a bearer token.
- Token stored encrypted on the phone, hashed or managed by Sanctum on the server, and revocable.
- No third-party SDKs, analytics, or cloud services anywhere.
- Don't log tokens or full payloads in production logs.
- Webapp: CSRF protection on web routes, password-protected UI, and registration disabled.

---

## 6. Suggested order of work
1. **Phase 0:** Gadgetbridge discovery, then write `DISCOVERY.md` (stop and wait for review).
2. **Webapp skeleton:** migrations, token auth, `/ping` and `/ingest` with tests. Seed fake data.
3. **Android Phase 1, monitor-only:** Night screen, foreground service, fetch loop, own DB and events, window/deadline logic, backup alarm.
4. **Android server sync:** settings, WorkManager, idempotent upload, end-to-end test against the webapp.
5. **Webapp UI:** nights list, night detail graphs, export, trends.
6. **Android Phase 2, smart wake:** detector interface, `HeuristicDetector` v1, simulation harness, acknowledgement flow, morning rating.
7. Polish: error states, pre-sleep checklist, docs (`SLEEPWAKE_CHANGES.md`, README, manual test checklist).

At the end of each step, summarize what changed and what to verify on a real device. Ask me before making any decision that affects upstream compatibility.
