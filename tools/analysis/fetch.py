"""Download the data from the webapp (analyze token) into tools/analysis/data/ (git-ignored).

    python tools/analysis/fetch.py            # last 90 days
    python tools/analysis/fetch.py --days 30
"""
import argparse
import datetime
import json
import os

import common

ap = argparse.ArgumentParser()
ap.add_argument("--days", type=int, default=90)
a = ap.parse_args()

data = common.api("GET", f"/api/v1/export?days={a.days}")
os.makedirs(common.DATA_DIR, exist_ok=True)
stamp = datetime.date.today().isoformat()
for name in (f"export-{stamp}.json", "latest.json"):
    with open(os.path.join(common.DATA_DIR, name), "w", encoding="utf8") as fh:
        json.dump(data, fh)
print(f"sessions={len(data['sessions'])} samples={len(data['samples'])} events={len(data['events'])} "
      f"configs={len(data['config_history'])} range={data['range']}")
print("saved to", common.latest_path())
