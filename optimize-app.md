# optimize-app.md — daily optimization of the wake-up algorithm

The owner says "read optimize-app.md" after a night. Then do this file, top to bottom. Goal: wake the owner in **light sleep**
as precisely as possible, using the nights stored in the webapp. Optimize **the algorithm** (data mining on the nights), not only
the app. Every day the algorithm should be a bit better, and every change must be written in the log.

Talk to the owner in simple English (short sentences, see `~/.claude/CLAUDE.md`). This file is for you and may be technical.

## 1. Map

| What | Where |
|---|---|
| Webapp (data, graphs, API) | https://gadget.peppasoft.com, Laravel in `webapp/` (deploy: `bash deploy/deploy.sh`) |
| Android app (Gadgetbridge fork) | `android/`, module in `android/app/src/sleepwake/`, notes in `android/SLEEPWAKE.md`, `DISCOVERY.md` |
| Algorithm, Kotlin | `android/app/src/sleepwake/java/.../sleepwake/core/` (`ScoredDetector.kt`, `Detectors.kt`, `WakeEngine.kt`) |
| Algorithm, Python twin (for fitting) | `tools/analysis/detector.py` (same numbers as Kotlin, checked by `testvectors.json`) |
| Analysis tools | `tools/analysis/` (`fetch.py`, `nights.py`, `fit.py`, `push_config.py`) |
| Health data (private!) | `tools/analysis/data/` is git-ignored. **Never commit or paste raw health data anywhere public** (the repo is public) |
| Log of what was done | `reference-docs/optimization-log.md` (read it first, append to it last) |
| Credentials | `E:\www\myLan\credentials.md` (the token "analysis (Claude)" has ability `analyze`: read all data, write the detector config) |

How the algorithm reaches the phone: the app downloads the newest **detector config** (JSON) from the webapp at each sync and just
before a night starts. A parameter change = push a new config, **no app build needed**. A new feature or a new detector type =
change Kotlin + Python, run the tests, build, install with adb.

How a night works (so you know what the data means): the app records the whole night. Every 5 minutes (setting) it fetches the
watch, evaluates the detector (event `detector_evaluated`, with all inputs) and, inside the window `target ± tolerance`, wakes the
owner at the first moment the detector says "light sleep". At the end of the window it always wakes the owner (`DEADLINE`).
That deadline rule is **not** part of the detector and must never be touched.

## 2. Daily routine

1. `git pull`, then read `reference-docs/optimization-log.md` (what was tried, what is pending).
2. Get the data:
   ```bash
   cd tools/analysis
   python fetch.py --days 90
   python nights.py            # table + data-quality flags + latency + wake reasons + ratings
   ```
3. **Gate 1, data quality** (stop and tell the owner if one fails):
   - Did last night produce a session with samples (`n_samp` about 400+ for 7–8 h)? Flags `FEW_SAMPLES`, `GAPS`, `DROPS`, `FETCH_FAIL`, `LOW_BATT`, `OPEN`.
   - Was the watch worn the whole night (HR present, not flat)? Was the deadline respected (woke at or before the window end)?
   - `NO_STAGES`: the watch sleep stages are missing, so there is no proxy label. Say so; do not fit.
4. **Gate 2, how many nights?** Use the table in section 4 to decide what is allowed today.
5. Evaluate and, if allowed, fit:
   ```bash
   python fit.py                    # replays the current detector on all nights, fits a candidate, leave-one-night-out check
   python fit.py --write            # writes data/candidate-config.json
   ```
   Add `--delay N` with the measured data delay (median `lat_med` from `nights.py`, in minutes) so the replay sees the data as late as the real night did.
6. Look at the numbers, think, and decide (section 5). Prefer small, explainable changes.
7. Apply (section 6), then **append the log entry** (section 7), commit and push.
8. Tell the owner the result in 5–8 short lines: what you saw last night, what you changed, what to do tonight.

## 3. What "good" means

The truth ("was the owner woken from light sleep?") is not measured directly. Use these, from the weakest to the strongest:

1. **Proxy**: the watch's own stage at the minute the app woke the owner (`light` / `awake` = good, `deep` = bad). Weak: wrist staging is rough, REM and light are not separable. Use it for fitting and for replays.
2. **Wake ease** (`ease` in the `morning_rating` event, 1 easy … 3 hard/groggy) — sleep inertia is the best simple sign of waking from deep sleep.
3. **Morning rating** (1–5, `morning_rating`), plus the note.
4. Outcome counts: how many nights woke early (`LIGHT_SLEEP`) vs at the deadline (`DEADLINE`), and how many minutes early. If the detector almost never fires early, it is too strict. If it fires at the first minute of the window every night, it is too loose (it should pick a *good* moment, not just any moment).
5. Safety: no night may wake after the window end. No `FALLBACK` wakes caused by software (only by a real disconnect).

Compare detectors on **leave-one-night-out** numbers only. In-sample numbers flatter the fit.
Beware of circular tests: the default detector uses the watch stage as a feature, so "stage at the chosen minute" is partly circular for it. Fitting always excludes stage features because the label is the stage.

## 4. What is allowed, by number of nights with data

| Nights with stages | Allowed |
|---|---|
| 0–2 | Only verify the pipeline (gate 1) and the open questions in section 8. No config change. |
| 3–9 | No fitting. At most one threshold/weight nudge by reasoning (e.g. fewer early wakes → raise `threshold` a little), one change at a time. |
| 10–29 | `fit.py` is allowed. Take the candidate only if leave-one-night-out beats the current detector on the proxy **and** it does not wake at the window start every night. Push as a new config version. |
| 30+ | Personalize: estimate the owner's real cycle length (try `cycle_len_min` 80–110 with `fit.py`), check weekday effects, and add or remove features. Code changes are allowed (Kotlin + Python + vectors + tests). |

Rules that always hold:

- **One change per day**, so the effect can be seen. Write down what you expect.
- Never touch the deadline logic (`WakeEngine`, `WakeWindow`, the hard alarm) without being asked. Run the unit tests after any Kotlin change.
- Do not overfit: 3 parameters at most when there are fewer than 20 nights. Keep `l2` regularization.
- If the owner says the wake-ups got worse, go back to the previous config version (push the old JSON as a new version) first, think second.
- Use the ratings as the final judge. After 14+ rated nights, check the correlation between "woke at a detector-chosen time" and rating/ease. If the fitted detector is not better than `DEADLINE` wakes on ratings, say so honestly.

## 5. Thinking checklist (data mining)

Look for these and write down what you find:

- **Latency**: is the newest sample usually minutes old when a fetch finishes? A detector cannot react to what it has not seen. Choose the fetch interval and `max_stale_min` from the measurement. If the median latency is longer than the window, early wake cannot work well: say so.
- **Stages live?** (`stage_samples` in `fetch_finished`): if the watch writes the sleep stages only after the night, stage features are useless live. Then set the stage weights to 0 and rely on HR and movement.
- **Movement quality**: `movement` = steps delta or intensity. If it is always 0 at night, drop `mov` and lean on HR. If it spikes only when the owner is already awake, it is a late signal.
- **HR shape**: per night, plot or print HR in the last 2 h: is there a rise before the natural waking? Is `hr_delta` positive on `light` minutes? Check the fitted signs (a negative `hr_sd` or `cycle_end` weight says the feature does not mean what the prior thought).
- **Cycle phase**: sleep cycles are about 90 minutes. Does the stage data show light/awake near multiples of the cycle from onset? Fit `cycle_len_min`.
- **Sleep onset estimate**: is `estimate_onset` sane (compare with the first deep stage)? A bad onset breaks `hr_base` and `cycle_end`.
- **Window use**: with a ±15 min window there are only 6 evaluations. The best algorithm picks the best of them, not the first acceptable one. Ideas: require the score to be a local maximum, or lower the threshold with `t_frac` (already there, via weight).
- **Tolerance**: the owner decides the window. You may suggest a longer one if early wakes are rare because the window is too short. Never change it yourself.

## 6. Applying a change

**Config only (preferred):**
```bash
python tools/analysis/push_config.py data/candidate-config.json --note "2026-10-21: refit on 12 nights, LONO proxy 0.71 (was 0.58)"
```
The phone downloads it at its next sync (and right before the next night starts if sync is on). Check with `push_config.py --show`.
The night's `detector_params.config_version` in the webapp shows which version a night used.

**Code change** (new feature, new detector type, bug):
1. Edit Kotlin in `android/.../core/` **and** `tools/analysis/detector.py` the same way. Add the detector id to `ConfigController::DETECTORS` in `webapp/` if it is new, then deploy the webapp.
2. `python tools/analysis/make_testvectors.py` to regenerate the vectors.
3. Tests (the quotes matter, the shell must not expand the `*`):
   ```bash
   cd android
   export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
   ./gradlew assembleSleepwakeDebug testSleepwakeDebugUnitTest --tests 'nodomain.freeyourgadget.gadgetbridge.sleepwake.*'
   ```
   (the first build takes about 25 minutes, later ones a few minutes; run it in the background). All module tests must pass.
4. Install on the phone (it must be connected by USB debugging; the same debug key is used, so it updates in place and keeps the data):
   ```bash
   "/c/Users/fateh/AppData/Local/Android/Sdk/platform-tools/adb.exe" install --user 0 -r app/build/outputs/apk/sleepwake/debug/app-sleepwake-debug.apk
   ```
   If `adb devices` shows no phone, tell the owner to connect it (or install the APK by hand). Do not uninstall the app: that deletes the paired watch and the night data on the phone.
5. Commit and push (`git add -A; git commit; git push`). Webapp changes: `bash deploy/deploy.sh`.

## 7. The log

Append to `reference-docs/optimization-log.md` (newest at the bottom), always this shape:

```
## YYYY-MM-DD — night of D (nights with data: N, with stages: M)
- Last night: wake reason, minutes before deadline, rating/ease, flags
- Data quality: latency median, gaps, drops
- Finding: ...
- Change: config vN (what, why) | code (what) | none
- Expectation / how to check tomorrow: ...
```

## 8. Open questions the first nights must answer (write the answers into `DISCOVERY.md`, section "Measured")

1. Data delay: median/p90 of `latency_s`. Can the watch be fetched every 5 minutes while worn (any `fetch_failed`)?
2. Are sleep stages available during the night (`stage_samples` > 0 in mid-night `fetch_finished`) or only after?
3. Is `movement` (steps/intensity) non-zero when the owner turns in bed?
4. How long does one find-watch vibration last, and does the repeat every 30 s annoy or wake properly? (ask the owner)
5. Does the watch record HR every minute at night in "smart" mode, or sparser?
6. Does Doze or battery saving delay ticks (gaps between `fetch_started` events > 5 min)?

## 9. Day data (does the watch need to be worn in the daytime?)

Not needed to start. The detector only uses the night. Day wear would add: a daytime resting-HR baseline (to normalize the night HR), activity load, and naps. After about 3–4 weeks of nights, if the night-only model plateaus, ask the owner whether to wear it by day for 1–2 weeks to test if a day baseline helps. Until then do not ask. (The webapp stores samples outside sessions too, so day data would be kept without changes.)

## 10. Report template for the owner (simple English)

```
Last night: woke at 07:52, in the window (07:45–08:15), light sleep per the watch. Rating 4, easy.
Data: good, 470 samples, 1 short gap.
Today I changed: <one thing> because <reason>. (config v5)
Tonight: nothing special, start the session as usual. Please rate in the morning, including "was waking easy?".
```
