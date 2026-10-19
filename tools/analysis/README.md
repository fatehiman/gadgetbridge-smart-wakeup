# tools/analysis

Scripts for the daily optimization (see [../../optimize-app.md](../../optimize-app.md)). Python 3 with numpy only.

| Script | What |
|---|---|
| `fetch.py [--days N]` | downloads the nights from the webapp (analyze token) into `data/` (git-ignored: health data) |
| `nights.py` | per-night table, data-quality flags, latency, wake reasons, ratings |
| `fit.py [--write] [--delay N]` | replays the detector, fits a candidate with a logistic regression on the watch-stage proxy, leave-one-night-out check |
| `push_config.py FILE --note "..."` | publishes a detector config (the phone downloads it) |
| `detector.py` | Python twin of the Kotlin `ScoredDetector` |
| `make_testvectors.py` | writes `testvectors.json`, which the Kotlin test `ScoredDetectorTest` checks |
| `make_fake_export.py` | FAKE data (`data/fake.json`) to try the tools; use `--file data/fake.json`. Never decide anything from it |

The token comes from `GADGET_ANALYZE_TOKEN` or the "analysis (Claude)" line of `E:\www\myLan\credentials.md`.
