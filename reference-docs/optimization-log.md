# Optimization log

Newest entry at the bottom. Format and rules: [../optimize-app.md](../optimize-app.md), section 7. Do not put raw health data here (the repo is public): numbers like counts, medians and minutes are fine.

## 2026-10-19 — setup (nights with data: 0)
- Built: webapp (ingest API, nights, trends, export, cleanup), Android module, detector config download, analysis tools.
- Detector in use: built-in `heuristic_v1` (movement in the last 5 min, or HR above baseline by 8 bpm). `scored_v1` exists but no config is pushed yet. Both only act inside the window; the deadline always fires.
- Analysis tools tested on FAKE data only (`tools/analysis/make_fake_export.py`). The fitted numbers from fake data mean nothing.
- Pending: first real nights. Answer the open questions in optimize-app.md section 8 (latency, live stages, movement, find-watch duration).
- Known unknown: whether the watch auth key works (see android/SLEEPWAKE.md "Pairing").
