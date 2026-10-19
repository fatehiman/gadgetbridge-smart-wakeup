"""Python port of the app's ScoredDetector (android/.../sleepwake/core/ScoredDetector.kt).

It must give the same numbers as the Kotlin code: `make_testvectors.py` writes testvectors.json from this file and the
Kotlin unit test (ScoredDetectorTest) checks the Kotlin code against it. If you change the features here, change the
Kotlin code too, regenerate the vectors, and run both.

A sample is a dict: {"ts": epoch seconds, "hr": int|None, "mov": int|None, "stage": str|None}.
"""
import math

DEFAULT_WEIGHTS = {
    "mov": 1.5, "hr_delta": 0.12, "hr_slope": 0.15, "hr_sd": 0.1,
    "stage_light": 1.0, "stage_rem": 0.3, "stage_deep": -1.5,
    "t_frac": 1.0, "cycle_end": 0.5,
}
FEATURES = list(DEFAULT_WEIGHTS)

DEFAULT_PARAMS = {
    "recent_min": 5, "slope_win_min": 10, "base_percentile": 20, "onset_quiet_min": 10, "max_stale_min": 20,
    "stage_lookback_min": 10, "cycle_len_min": 90, "bias": -1.0, "threshold": 0.0,
}


def merged_params(params=None):
    p = dict(DEFAULT_PARAMS)
    w = dict(DEFAULT_WEIGHTS)
    if params:
        for k, v in params.items():
            if k == "weights":
                w.update(v)
            elif k in p:
                p[k] = v
    p["weights"] = w
    return p


def median(v):
    if not v:
        return None
    s = sorted(v)
    m = len(s) // 2
    return float(s[m]) if len(s) % 2 == 1 else (s[m - 1] + s[m]) / 2.0


def percentile(v, pct):
    if not v:
        return None
    s = sorted(v)
    rank = min(max(math.ceil(pct / 100.0 * len(s)), 1), len(s))
    return float(s[rank - 1])


def estimate_onset(samples, session_start, quiet_min):
    quiet = quiet_min * 60
    quiet_since = None
    for x in samples:
        if (x.get("mov") or 0) > 0:
            quiet_since = None
        else:
            if quiet_since is None:
                quiet_since = x["ts"]
            if x["ts"] - quiet_since >= quiet:
                return quiet_since
    return session_start


def clip(x, lo, hi):
    return max(lo, min(hi, x))


def features(samples, now, window_start, deadline, session_start, params=None):
    """Returns (features dict, stale bool, last_sample_age_min)."""
    p = merged_params(params)
    s = [x for x in samples if x["ts"] <= now]
    age = (now - s[-1]["ts"]) // 60 if s else None
    stale = age is None or age > p["max_stale_min"]

    def window(from_min, to_min):
        return [x for x in s if now - from_min * 60 < x["ts"] <= now - to_min * 60]

    def hr(lst):
        return [x["hr"] for x in lst if x.get("hr") is not None and x["hr"] > 0]

    recent = window(p["recent_min"], 0)
    mov = min(sum(1 for x in recent if (x.get("mov") or 0) > 0), 3) / 3.0
    onset = estimate_onset(s, session_start, p["onset_quiet_min"])
    base = percentile(hr([x for x in s if x["ts"] >= onset]), p["base_percentile"])
    hr_recent = median(hr(recent))
    hr_delta = clip(hr_recent - base, -10.0, 30.0) if hr_recent is not None and base is not None else 0.0
    cur = median(hr(window(p["slope_win_min"], 0)))
    prev = median(hr(window(2 * p["slope_win_min"], p["slope_win_min"])))
    slope = clip(cur - prev, -10.0, 20.0) if cur is not None and prev is not None else 0.0
    hw = hr(window(p["slope_win_min"], 0))
    if len(hw) >= 3:
        mean = sum(hw) / len(hw)
        sd = clip(math.sqrt(sum((v - mean) ** 2 for v in hw) / len(hw)), 0.0, 10.0)
    else:
        sd = 0.0
    stage = None
    for x in reversed(s):
        if x.get("stage") is not None and x["ts"] > now - p["stage_lookback_min"] * 60:
            stage = x["stage"]
            break
    wl = float(deadline - window_start)
    t_frac = 1.0 if wl <= 0 else clip((now - window_start) / wl, 0.0, 1.0)
    since_onset_min = (now - onset) / 60.0
    cyc = p["cycle_len_min"]
    pos = (since_onset_min % cyc) / cyc
    cycle_end = max(0.0, 1.0 - min(pos, 1 - pos) * 4)
    f = {
        "mov": mov, "hr_delta": hr_delta, "hr_slope": slope, "hr_sd": sd,
        "stage_light": 1.0 if stage in ("light", "awake") else 0.0,
        "stage_rem": 1.0 if stage == "rem" else 0.0,
        "stage_deep": 1.0 if stage == "deep" else 0.0,
        "t_frac": t_frac, "cycle_end": cycle_end,
    }
    return f, stale, age


def score(f, params=None):
    p = merged_params(params)
    return p["bias"] + sum(p["weights"].get(k, 0.0) * v for k, v in f.items())


def decide(samples, now, window_start, deadline, session_start, params=None):
    """Same decision as ScoredDetector.evaluate: (wake: bool, score, features, stale)."""
    p = merged_params(params)
    f, stale, _ = features(samples, now, window_start, deadline, session_start, p)
    sc = score(f, p)
    return (not stale and sc >= p["threshold"]), sc, f, stale


def simulate_night(samples, window_start, deadline, session_start, params=None, tick_min=5, fetch_delay_min=0):
    """Replays the detector like the app does: evaluate every tick_min from window start to the deadline.
    Returns (wake_ts, reason) where reason is 'LIGHT_SLEEP' or 'DEADLINE'."""
    t = window_start
    while t < deadline:
        visible = [x for x in samples if x["ts"] <= t - fetch_delay_min * 60]
        wake, _, _, _ = decide(visible, t, window_start, deadline, session_start, params)
        if wake:
            return t, "LIGHT_SLEEP"
        t += tick_min * 60
    return deadline, "DEADLINE"
