"""Fit and evaluate the light-sleep detector on the recorded nights.

    python tools/analysis/fit.py                 # evaluate the current config, fit a candidate, compare (leave-one-night-out)
    python tools/analysis/fit.py --write         # also write candidate-config.json (for push_config.py)

What it does
1. Training rows: every minute of the last `--horizon` minutes of each night. Features come from detector.features()
   exactly as the app computes them (with the data delay of `--delay` minutes).
2. Proxy label (the truth is unknown): the watch's own sleep stage. positive = light/awake, negative = deep, REM rows are
   left out (a wrist sensor cannot tell REM from light). Stage features are NOT used for the fit (they would leak the label).
   Needs nights that have stages (see NO_STAGES in nights.py).
3. Logistic regression (numpy, L2) on mov, hr_delta, hr_slope, hr_sd, cycle_end. t_frac (urgency) and the stage weights stay
   at their prior values.
4. Leave-one-night-out check: for each night fit on the others, replay the detector over that night's wake window and report
   how often it wakes early, how early, and whether the proxy says "light/awake" at the chosen minute.
5. If morning ratings exist: mean rating / ease per wake reason (the real target, usually too few nights to fit).
"""
import argparse
import collections
import json
import math
import os
import sys

import numpy as np

import common
import detector

FIT_FEATURES = ["mov", "hr_delta", "hr_slope", "hr_sd", "cycle_end"]

ap = argparse.ArgumentParser()
ap.add_argument("--file")
ap.add_argument("--horizon", type=int, default=150, help="minutes before the night end used for training")
ap.add_argument("--delay", type=int, default=0, help="assumed data delay in minutes (see latency in nights.py)")
ap.add_argument("--p", type=float, default=0.6, help="probability of light sleep needed to wake (operating point)")
ap.add_argument("--l2", type=float, default=1.0)
ap.add_argument("--min-nights", type=int, default=10)
ap.add_argument("--write", action="store_true")
ap.add_argument("--force", action="store_true")
a = ap.parse_args()

data = common.load(a.file)
nights = [n for n in common.night_list(data) if len(n["samples"]) > 120 and n["s"].get("window_start") and n["s"].get("deadline")]
print(f"{len(nights)} usable night(s)")
staged = [n for n in nights if any(x["stage"] for x in n["samples"])]
print(f"{len(staged)} night(s) with watch sleep stages")


def current_params():
    cfgs = data.get("config_history") or []
    if cfgs and cfgs[-1]["config"].get("detector") == "scored_v1":
        return cfgs[-1]["config"].get("params")
    return None


def night_rows(n):
    """(X, y, minutes) rows for one night, labels from the stage proxy (REM and unknown rows dropped)."""
    s = n["s"]
    smp = n["samples"]
    end = n["wake_ts"] or n["end"]
    rows = []
    for t in range(end - a.horizon * 60, end, 60):
        stage = None
        for x in reversed([x for x in smp if x["ts"] <= t]):
            if x["stage"] is not None and x["ts"] > t - 600:
                stage = x["stage"]
                break
        if stage not in ("light", "awake", "deep"):
            continue
        vis = [x for x in smp if x["ts"] <= t - a.delay * 60]
        # stage features removed: the label is the stage
        nostage = [dict(x, stage=None) for x in vis]
        f, stale, _ = detector.features(nostage, t, s["window_start"], s["deadline"], s["started_at"])
        if stale:
            continue
        rows.append(([f[k] for k in FIT_FEATURES], 1.0 if stage in ("light", "awake") else 0.0))
    return rows


def fit_logistic(X, y, l2):
    X = np.asarray(X, float)
    y = np.asarray(y, float)
    mu = X.mean(0)
    sd = X.std(0)
    sd[sd < 1e-9] = 1.0
    Z = np.hstack([np.ones((len(X), 1)), (X - mu) / sd])
    w = np.zeros(Z.shape[1])
    reg = np.eye(Z.shape[1]) * l2
    reg[0, 0] = 0
    for _ in range(60):  # Newton / IRLS
        p = 1 / (1 + np.exp(-Z @ w))
        g = Z.T @ (p - y) + reg @ w
        H = (Z.T * (p * (1 - p))) @ Z + reg + 1e-6 * np.eye(Z.shape[1])
        step = np.linalg.solve(H, g)
        w -= step
        if np.abs(step).max() < 1e-8:
            break
    raw_w = w[1:] / sd
    raw_b = w[0] - float((w[1:] * mu / sd).sum())
    return raw_w, raw_b


def params_from_fit(raw_w, raw_b, p_star):
    prior = detector.DEFAULT_WEIGHTS
    weights = dict(prior)
    for k, v in zip(FIT_FEATURES, raw_w):
        weights[k] = round(float(v), 4)
    # score = bias + sum(w f); the fitted logit already contains the intercept; t_frac adds urgency near the deadline
    return {"bias": round(float(raw_b), 4), "threshold": round(math.log(p_star / (1 - p_star)), 4), "weights": weights}


def simulate(n, params):
    t, reason = detector.simulate_night(n["samples"], n["s"]["window_start"], n["s"]["deadline"], n["s"]["started_at"], params, fetch_delay_min=a.delay)
    stage = None
    for x in reversed([x for x in n["samples"] if x["ts"] <= t]):
        if x["stage"] is not None and x["ts"] > t - 600:
            stage = x["stage"]
            break
    return t, reason, stage


def evaluate(nlist, params_for, label):
    reasons = collections.Counter()
    early = []
    proxy = collections.Counter()
    for n in nlist:
        t, reason, stage = simulate(n, params_for(n))
        reasons[reason] += 1
        early.append((n["s"]["deadline"] - t) / 60)
        if stage:
            proxy["light/awake" if stage in ("light", "awake") else stage] += 1
    print(f"  {label}: {dict(reasons)}  mean minutes before deadline {sum(early) / len(early):.1f}  watch stage at chosen minute {dict(proxy)}")


if len(staged) < a.min_nights and not a.force:
    print(f"\nOnly {len(staged)} night(s) with stages; need {a.min_nights} to fit (use --force to override). Evaluating the current setting only.")
    if nights:
        evaluate(nights, lambda n: current_params(), "current config")
    sys.exit(0)

rows_by_night = {id(n): night_rows(n) for n in staged}
all_rows = [r for rs in rows_by_night.values() for r in rs]
pos = sum(y for _, y in all_rows)
print(f"training rows: {len(all_rows)}  (light/awake {int(pos)}, deep {int(len(all_rows) - pos)})")
if len(all_rows) < 200 or pos < 20 or len(all_rows) - pos < 20:
    print("Not enough rows of both kinds to fit. Collect more nights.")
    if not a.force:
        sys.exit(0)

X = [r[0] for r in all_rows]
y = [r[1] for r in all_rows]
w, b = fit_logistic(X, y, a.l2)
cand = params_from_fit(w, b, a.p)
print("\nFitted weights:", {k: round(float(v), 4) for k, v in zip(FIT_FEATURES, w)}, "bias", round(float(b), 4))

# in-sample quality of the proxy classifier
Z = np.asarray(X) @ w + b
pred = Z >= 0
acc = float((pred == (np.asarray(y) > 0.5)).mean())
print(f"proxy accuracy (in-sample, threshold p=0.5): {acc:.2f}   base rate of light/awake: {pos / len(all_rows):.2f}")

print("\nEnd-to-end replay over each night's wake window (data delay %d min):" % a.delay)
evaluate(nights, lambda n: None, "built-in default weights")
cur = current_params()
if cur:
    evaluate(nights, lambda n: cur, "current config (in-sample)")
evaluate(staged, lambda n: cand, "candidate (in-sample)")


def lono(n):
    others = [m for m in staged if m is not n]
    rows = [r for m in others for r in rows_by_night[id(m)]]
    if len(rows) < 100:
        return None
    ww, bb = fit_logistic([r[0] for r in rows], [r[1] for r in rows], a.l2)
    return params_from_fit(ww, bb, a.p)


held = {id(n): lono(n) for n in staged}
if all(v is not None for v in held.values()) and len(staged) >= 3:
    evaluate(staged, lambda n: held[id(n)], "candidate, leave-one-night-out (the honest number)")

rated = [n for n in nights if n["rating"] and n["rating"].get("rating")]
if rated:
    by = collections.defaultdict(list)
    for n in rated:
        by[n["wake_reason"]].append(n["rating"]["rating"])
    print("\nMean morning rating by wake reason (real target):", {k: (round(sum(v) / len(v), 2), len(v)) for k, v in by.items()})

config = {"detector": "scored_v1", "params": cand}
print("\nCandidate config:\n" + json.dumps(config, indent=1))
if a.write:
    path = os.path.join(common.DATA_DIR, "candidate-config.json")
    with open(path, "w") as fh:
        json.dump(config, fh, indent=1)
    print("written to", path)
