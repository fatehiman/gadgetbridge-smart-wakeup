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
