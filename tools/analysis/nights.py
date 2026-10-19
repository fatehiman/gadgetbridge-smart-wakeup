"""Per-night report with data-quality checks. Run after fetch.py.

    python tools/analysis/nights.py [--last N]
"""
import argparse
import collections
import datetime

import common

ap = argparse.ArgumentParser()
ap.add_argument("--last", type=int, default=40)
ap.add_argument("--file")
a = ap.parse_args()

data = common.load(a.file)
nights = common.night_list(data)[-a.last:]
print(f"{len(nights)} night(s). Times in each night's own time zone.\n")
hdr = "date        window        woke   reason          dur   n_samp  hr avg/min  light/deep/rem/awake(min)  lat_med(min)  evals  rating/ease  cfg  flags"
print(hdr)
print("-" * len(hdr))
for n in nights:
    s, smp, ev = n["s"], n["samples"], n["events"]
    tz = s.get("timezone")
    hr = [x["hr"] for x in smp if x["hr"]]
    stages = collections.Counter(x["stage"] for x in smp if x["stage"])
    fetches = [e for e in ev if e["type"] == "fetch_finished"]
    lat = common.med([(e["payload"].get("latency_s") or None) for e in fetches if e["payload"].get("latency_s") is not None])
    evals = [e for e in ev if e["type"] == "detector_evaluated"]
    flags = []
    if len(smp) < 60:
        flags.append("FEW_SAMPLES")
    # gaps
    ts = [x["ts"] for x in smp]
    gaps = [b - a_ for a_, b in zip(ts, ts[1:]) if b - a_ > 15 * 60]
    if gaps:
        flags.append(f"GAPS({len(gaps)},max {max(gaps) // 60}m)")
    if any(e["type"] == "connection_lost" for e in ev):
        flags.append(f"DROPS({sum(1 for e in ev if e['type'] == 'connection_lost')})")
    if sum(1 for e in ev if e["type"] == "fetch_failed"):
        flags.append(f"FETCH_FAIL({sum(1 for e in ev if e['type'] == 'fetch_failed')})")
    batt = [e["payload"].get("level") for e in ev if e["type"] == "battery"]
    if batt and min(batt) < 20:
        flags.append(f"LOW_BATT({min(batt)}%)")
    if not stages:
        flags.append("NO_STAGES")
    if not s.get("ended_at"):
        flags.append("OPEN")
    r = n["rating"] or {}
    dur = (s["ended_at"] - s["started_at"]) / 3600 if s.get("ended_at") else None
    print(f"{datetime.datetime.fromtimestamp(s['started_at'], datetime.timezone.utc).strftime('%Y-%m-%d')}  "
          f"{common.hhmm(s.get('window_start'), tz)}-{common.hhmm(s.get('deadline'), tz)}  {common.hhmm(n['wake_ts'], tz)}  "
          f"{(n['wake_reason'] or '-'):<14} {dur if dur is None else round(dur, 1):>5}  {len(smp):>6}  "
          f"{(round(sum(hr) / len(hr)) if hr else '-'):>4}/{(min(hr) if hr else '-'):<5}  "
          f"{stages.get('light', 0):>4}/{stages.get('deep', 0)}/{stages.get('rem', 0)}/{stages.get('awake', 0)}  "
          f"{(round(lat / 60, 1) if lat is not None else '-'):>10}  {len(evals):>5}  "
          f"{r.get('rating', '-')}/{r.get('ease', '-')}  {n['params'].get('config_version', '-'):>3}  {' '.join(flags)}")

print("\nWake reasons:", dict(collections.Counter(n["wake_reason"] or "-" for n in nights)))
rated = [(n["rating"]["rating"], n["wake_reason"]) for n in nights if n["rating"] and n["rating"].get("rating")]
if rated:
    by = collections.defaultdict(list)
    for r, why in rated:
        by[why].append(r)
    print("Mean rating by wake reason:", {k: round(sum(v) / len(v), 2) for k, v in by.items()}, " (n =", {k: len(v) for k, v in by.items()}, ")")
ease = [(n["rating"]["ease"], n["wake_reason"]) for n in nights if n["rating"] and n["rating"].get("ease")]
if ease:
    by = collections.defaultdict(list)
    for r, why in ease:
        by[why].append(r)
    print("Mean wake ease (1 easy .. 3 hard) by reason:", {k: round(sum(v) / len(v), 2) for k, v in by.items()})
all_lat = [e["payload"].get("latency_s") for n in nights for e in n["events"] if e["type"] == "fetch_finished" and e["payload"].get("latency_s") is not None]
if all_lat:
    all_lat.sort()
    print(f"Fetch latency (newest sample age when a fetch finished): median {all_lat[len(all_lat) // 2] / 60:.1f} min, "
          f"p90 {all_lat[int(len(all_lat) * 0.9)] / 60:.1f} min, max {all_lat[-1] / 60:.1f} min, n={len(all_lat)}")
stage_live = [e["payload"].get("stage_samples") for n in nights for e in n["events"] if e["type"] == "fetch_finished" and e["payload"].get("stage_samples") is not None]
if stage_live:
    print(f"Watch sleep stages visible during the night: {sum(1 for x in stage_live if x)}/{len(stage_live)} fetches had stage samples")
