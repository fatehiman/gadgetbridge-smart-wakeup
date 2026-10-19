"""Makes a FAKE export (data/fake.json) to test the analysis tools without real nights. Never use it for decisions.

    python tools/analysis/make_fake_export.py [nights]
"""
import json
import math
import os
import random
import sys

import common

random.seed(7)
N = int(sys.argv[1]) if len(sys.argv) > 1 else 20
sessions, samples, events = [], [], []
for n in range(N):
    start = 1_760_000_000 + n * 86400
    end = start + 8 * 3600
    target = end - 900
    ws, dl = target - 900, target + 900
    uuid = f"fake-{n}"
    # hidden truth: 90 min cycles, light sleep near cycle ends
    for i in range(0, 8 * 60 + 3):
        t = start + i * 60
        phase = (i % 90) / 90.0
        light = phase > 0.8 or phase < 0.08
        stage = "light" if light else random.choice(["deep", "deep", "rem"]) if phase < 0.5 else "rem"
        hr = int(52 + (6 if light else 0) + 3 * math.sin(i / 11) + random.randint(-2, 2))
        mov = 1 if (light and random.random() < 0.4) else 0
        samples.append({"session_uuid": uuid, "device": "fake", "ts": t, "heart_rate": hr, "steps": 0, "intensity": 0, "movement": mov,
                        "spo2": None, "sleep_stage": stage, "extra": None})
    sessions.append({"uuid": uuid, "device": "fake", "started_at": start, "ended_at": end, "mode": "smart_wake", "target_time": target,
                     "tolerance_min": 15, "window_start": ws, "deadline": dl, "detector_id": "heuristic_v1",
                     "detector_params": {"config_version": 0}, "timezone": "UTC", "wake_reason": "DEADLINE",
                     "morning_rating": None, "notes": None})
    events.append({"session_uuid": uuid, "device": "fake", "ts": dl * 1000, "type": "wake_triggered", "payload": {"reason": "DEADLINE"}})
    events.append({"session_uuid": uuid, "device": "fake", "ts": (dl + 60) * 1000, "type": "morning_rating", "payload": {"rating": random.randint(2, 5), "ease": random.randint(1, 3)}})
    events.append({"session_uuid": uuid, "device": "fake", "ts": (start + 3600) * 1000, "type": "fetch_finished", "payload": {"latency_s": random.randint(60, 600), "stage_samples": 100}})
os.makedirs(common.DATA_DIR, exist_ok=True)
path = os.path.join(common.DATA_DIR, "fake.json")
json.dump({"sessions": sessions, "samples": samples, "events": events, "config_history": [], "range": {}}, open(path, "w"))
print("fake export written to", path)
