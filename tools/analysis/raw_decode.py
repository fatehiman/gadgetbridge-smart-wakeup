"""Look at the 16 unknown bytes of the CMF activity records (table sw_rawact of the app's sleepwake.db) and find out which
ones follow movement.

1. Pull the DB from the phone (debug build):
     adb exec-out run-as nodomain.freeyourgadget.gadgetbridge.sleepwake cat databases/sleepwake.db > data/sleepwake.db
2. Do a controlled test with the watch on the wrist and write the times in a labels file, e.g. data/labels.json:
     [{"start": "14:00", "end": "14:10", "label": "still"},
      {"start": "14:10", "end": "14:15", "label": "wave"},
      {"start": "14:15", "end": "14:20", "label": "walk"},
      {"start": "14:20", "end": "14:30", "label": "still"}]
   (times of the phone's clock, today, in --tz hours offset)
3. python tools/analysis/raw_decode.py --labels data/labels.json --tz 3.5

It prints every record, then per label the mean of each byte and of each little-endian 16-bit pair. Bytes that are
clearly higher in "wave"/"walk" than in "still" carry movement.
"""
import argparse
import datetime
import json
import os
import sqlite3
import statistics

import common

ap = argparse.ArgumentParser()
ap.add_argument("--db", default=os.path.join(common.DATA_DIR, "sleepwake.db"))
ap.add_argument("--labels")
ap.add_argument("--tz", type=float, default=3.5, help="phone time zone offset in hours (Iran 3.5)")
ap.add_argument("--date", help="YYYY-MM-DD of the test (default: today)")
ap.add_argument("--last", type=int, default=0, help="only the last N records")
a = ap.parse_args()

tz = datetime.timezone(datetime.timedelta(hours=a.tz))
con = sqlite3.connect(a.db)
rows = con.execute("select ts, steps, dist, cal, hex from sw_rawact order by ts").fetchall()
print(len(rows), "raw records")
if a.last:
    rows = rows[-a.last:]


def bytes_of(h):
    return [int(h[i:i + 2], 16) for i in range(0, len(h), 2)]


recs = [(ts, steps, dist, cal, bytes_of(h)) for ts, steps, dist, cal, h in rows]
for ts, steps, dist, cal, b in recs[-120:]:
    t = datetime.datetime.fromtimestamp(ts, tz).strftime("%m-%d %H:%M:%S")
    print(t, f"steps={steps} dist={dist} cal={cal}", " ".join(f"{x:02x}" for x in b))

# which byte positions change at all?
if recs:
    print("\nbyte position: distinct values over all records")
    for i in range(16):
        vals = [r[4][i] for r in recs]
        print(f"  b{i:02d}: distinct={len(set(vals)):3d} min={min(vals):3d} max={max(vals):3d}")

if a.labels:
    day = datetime.date.fromisoformat(a.date) if a.date else datetime.datetime.now(tz).date()
    labels = json.load(open(a.labels))

    def to_ts(hhmm):
        h, m = map(int, hhmm.split(":"))
        return int(datetime.datetime(day.year, day.month, day.day, h, m, tzinfo=tz).timestamp())

    groups = {}
    for L in labels:
        s, e = to_ts(L["start"]), to_ts(L["end"])
        groups.setdefault(L["label"], []).extend(r for r in recs if s <= r[0] < e)
    print("\nPer label: record count, mean steps, mean of each byte, mean of each 16-bit LE pair")
    for lab, rs in groups.items():
        if not rs:
            print(f"{lab}: no records")
            continue
        means = [statistics.mean(r[4][i] for r in rs) for i in range(16)]
        pairs = [statistics.mean(r[4][2 * i] + 256 * r[4][2 * i + 1] for r in rs) for i in range(8)]
        print(f"\n{lab}: n={len(rs)} steps_mean={statistics.mean(r[1] for r in rs):.1f}")
        print("  bytes:", " ".join(f"{m:6.1f}" for m in means))
        print("  u16  :", " ".join(f"{m:7.1f}" for m in pairs))
