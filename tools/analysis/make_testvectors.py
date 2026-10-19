"""Writes testvectors.json: random but fixed nights + the numbers detector.py computes for them.
The Kotlin test ScoredDetectorTest reads this file and must get the same numbers.

    python tools/analysis/make_testvectors.py
"""
import json
import math
import os
import random

import detector

random.seed(12345)
STAGES = [None, None, None, "deep", "light", "rem", "awake"]


def night(start, minutes, with_stage):
    out = []
    for i in range(minutes):
        t = start + i * 60
        cyc = math.sin(i / 90.0 * 2 * math.pi)
        hr = 54 + int(4 * cyc) + random.randint(-2, 2) + (12 if i > minutes - 25 else 0)
        mov = random.choice([0, 0, 0, 0, 0, 0, 1, 2])
        if random.random() < 0.05:
            hr = None
        out.append({"ts": t, "hr": hr, "mov": mov, "stage": random.choice(STAGES) if with_stage else None})
    return out


vectors = []
configs = [
    None,
    {"threshold": 0.5, "weights": {"mov": 2.0, "cycle_end": 0.2}},
    {"recent_min": 7, "slope_win_min": 8, "base_percentile": 30, "cycle_len_min": 100, "bias": -0.5},
]
for n in range(4):
    start = 1_760_000_000 + n * 86400
    minutes = 480
    samples = night(start, minutes, with_stage=n % 2 == 0)
    window_start = start + 420 * 60
    deadline = start + 450 * 60
    for cfg in configs:
        for off in (5, 12, 20, 29):
            now = window_start + off * 60 + 13  # not on a sample boundary
            f, stale, age = detector.features(samples, now, window_start, deadline, start, cfg)
            vectors.append({
                "params": cfg, "now": now, "window_start": window_start, "deadline": deadline, "session_start": start,
                "samples": [[x["ts"], x["hr"], x["mov"], x["stage"]] for x in samples if x["ts"] <= now],
                "expected": {"features": f, "score": detector.score(f, cfg), "stale": stale},
            })
# one stale case
start = 1_761_000_000
samples = night(start, 300, False)
now = start + 450 * 60
f, stale, age = detector.features(samples, now, start + 420 * 60, start + 450 * 60, start, None)
vectors.append({"params": None, "now": now, "window_start": start + 420 * 60, "deadline": start + 450 * 60, "session_start": start,
                "samples": [[x["ts"], x["hr"], x["mov"], x["stage"]] for x in samples],
                "expected": {"features": f, "score": detector.score(f, None), "stale": stale}})
assert vectors[-1]["expected"]["stale"] is True

path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testvectors.json")
with open(path, "w") as fh:
    json.dump(vectors, fh, separators=(",", ":"))
print(f"{len(vectors)} vectors written to {path} ({os.path.getsize(path) // 1024} KB)")
