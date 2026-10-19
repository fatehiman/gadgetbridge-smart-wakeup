"""Push a detector config to the webapp. The phone downloads it at its next sync and before a night starts.

    python tools/analysis/push_config.py data/candidate-config.json --note "why this change"
    python tools/analysis/push_config.py --show        # current config + history
"""
import argparse
import json

import common

ap = argparse.ArgumentParser()
ap.add_argument("file", nargs="?")
ap.add_argument("--note", default="")
ap.add_argument("--show", action="store_true")
a = ap.parse_args()

if a.show or not a.file:
    print(json.dumps(common.api("GET", "/api/v1/config/history"), indent=1)[:6000])
else:
    with open(a.file, encoding="utf8") as fh:
        cfg = json.load(fh)
    if not a.note:
        raise SystemExit("--note is required: say what changed and why")
    r = common.api("PUT", "/api/v1/config", {"config": cfg, "note": a.note})
    print("pushed, new version:", r["version"])
