# Optimization log

Newest entry at the bottom. Format and rules: [../optimize-app.md](../optimize-app.md), section 7. Do not put raw health data here (the repo is public): numbers like counts, medians and minutes are fine.

## 2026-10-19 — setup (nights with data: 0)
- Built: webapp (ingest API, nights, trends, export, cleanup), Android module, detector config download, analysis tools.
- Detector in use: built-in `heuristic_v1` (movement in the last 5 min, or HR above baseline by 8 bpm). `scored_v1` exists but no config is pushed yet. Both only act inside the window; the deadline always fires.
- Analysis tools tested on FAKE data only (`tools/analysis/make_fake_export.py`). The fitted numbers from fake data mean nothing.
- Pending: first real nights. Answer the open questions in optimize-app.md section 8 (latency, live stages, movement, find-watch duration).
- Known unknown: whether the watch auth key works (see android/SLEEPWAKE.md "Pairing").

## 2026-10-20 — night of 10-19 (nights with data: 1, with stages: 1)
- Last night: woke at the window end 09:00 (DEADLINE, window 08:00–09:00, tolerance ±30), the watch stage at 09:00 was light (lucky). Acknowledged after 27 s. No rating given.
- Data quality: good. Latency 15–45 s, 101 fetches, 1 timeout. HR every 5 min, SpO2 every 30 min. Movement 0 (nothing live). Sleep stages arrived only after the night (37 segments).
- Finding: HR means per stage are equal (79.8 / 80.1 / 81.2): HR cannot separate light and deep on this watch. The detector (heuristic_v1) never fired because HR stayed within 8 bpm of its baseline.
- Change (code, no config): wake alert is now a vibrating notification (no sound), phone notifications silenced on the watch during a night, SpO2/stress/stage/raw bytes/phone-motion are now collected and uploaded, the night chart shows the stage strip.
- Next: collect nights with phone motion + stress; check which signal follows the hypnogram (section 8b of optimize-app.md).

## 2026-10-20 (evening) — daytime check
- Owner confirmed: the new wake alert buzzes once without sound.
- Daytime data (walking 12:00–16:00) shows no minute-level movement from the watch: hourly totals only, HR every 5 min, SpO2 every 30 min. Conclusion: the live signals available are HR (flat across stages), SpO2, stress (from tonight), and the phone's accelerometer. Whether phone motion follows the watch stages is untested: tonight is the first test.
- Website: night page now has a color legend with minutes per stage.

## 2026-10-21 — night of 10-20 (nights with data: 2, with stages: 2)
- Last night: woke at the window end 09:00 (DEADLINE, window 08:00–09:00), watch stage at 09:00 light. No rating yet. Night 1 rating (found in the session): 3, note "smart alarm didn't work!".
- Data quality: good. 557 samples, 92 evaluations, latency median 0.1 min, p90 0.8 min, 1 fetch timeout. Stages again only after the night (0/193 fetches live).
- Finding: HR, SpO2, stress = chance (AUC 0.46–0.50). Phone motion, past 10 min: AUC 0.60–0.62, the only live signal above chance (1 night). Raw bytes all zero. Watch stages alternate every 7–14 min; the window had 77–83 % non-deep minutes, so the deadline wake was already "light" on both nights. Room to improve on the proxy is small (~20 %).
- Change: none (2 nights, section 4 allows no change).
- Expectation / how to check tomorrow: phone motion AUC stays ≥ 0.6 over the next nights → add a `phone_motion` feature to `scored_v1` (Kotlin + Python) at night 5. If it falls to ~0.5, the honest end state is a plain alarm (see 8b). Need morning ratings + ease every night: they are the real judge, because the proxy wakes "light" ~80 % of the time anyway.
