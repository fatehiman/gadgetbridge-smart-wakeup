"""Shared helpers: token/URL, data loading, per-night structures."""
import json
import os
import re
import statistics
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(HERE, "data")  # health data: git-ignored, never commit
BASE_URL = os.environ.get("GADGET_URL", "https://gadget.peppasoft.com").rstrip("/")
CREDENTIALS = os.environ.get("GADGET_CREDENTIALS", r"E:\www\myLan\credentials.md")


def token():
    t = os.environ.get("GADGET_ANALYZE_TOKEN")
    if t:
        return t.strip()
    try:
        with open(CREDENTIALS, encoding="utf8") as fh:
            for line in fh:
                if "analysis (Claude)" in line:
                    m = re.search(r"`(sw_[A-Za-z0-9]+)`", line)
                    if m:
                        return m.group(1)
    except OSError:
        pass
    sys.exit("No analyze token: set GADGET_ANALYZE_TOKEN or keep the 'analysis (Claude)' line in credentials.md")


def api(method, path, body=None, timeout=120):
    req = urllib.request.Request(BASE_URL + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Authorization", "Bearer " + token())
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "gadget-analysis/1.0 (curl-compatible)")  # Cloudflare blocks the default Python signature (error 1010)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} on {method} {path}: {e.read().decode()[:500]}")


def latest_path():
    return os.path.join(DATA_DIR, "latest.json")


def load(path=None):
    path = path or latest_path()
    if not os.path.exists(path):
        sys.exit(f"{path} not found: run fetch.py first")
    with open(path, encoding="utf8") as fh:
        return json.load(fh)


def jloads(x):
    if isinstance(x, str):
        try:
            return json.loads(x)
        except ValueError:
            return None
    return x


def night_list(data):
    """One dict per session: session row, samples (as detector dicts), events, derived values."""
    samples = sorted(data["samples"], key=lambda s: s["ts"])
    events = {}
    for e in data["events"]:
        events.setdefault(e["session_uuid"], []).append(dict(e, payload=jloads(e.get("payload")) or {}))
    nights = []
    for s in data["sessions"]:
        start = s["started_at"]
        end = s["ended_at"] or (start + 86400)
        rows = [x for x in samples if x["session_uuid"] == s["uuid"]]
        det = [{"ts": x["ts"], "hr": x["heart_rate"], "mov": x["movement"], "stage": x["sleep_stage"], "steps": x["steps"], "spo2": x["spo2"]} for x in rows]
        ev = sorted(events.get(s["uuid"], []), key=lambda e: e["ts"])
        wake = next((e for e in ev if e["type"] == "wake_triggered"), None)
        rating = next((e["payload"] for e in reversed(ev) if e["type"] == "morning_rating"), None)
        nights.append({
            "s": s, "samples": det, "events": ev, "start": start, "end": end,
            "wake_ts": (wake["ts"] // 1000) if wake else s["ended_at"],
            "wake_reason": s.get("wake_reason") or ((wake or {}).get("payload") or {}).get("reason"),
            "rating": rating,
            "params": jloads(s.get("detector_params")) or {},
        })
    return nights


def med(v):
    v = [x for x in v if x is not None]
    return statistics.median(v) if v else None


def hhmm(ts, tz_name=None):
    from datetime import datetime, timezone
    try:
        from zoneinfo import ZoneInfo
        tz = ZoneInfo(tz_name) if tz_name else timezone.utc
    except Exception:
        tz = timezone.utc
    return datetime.fromtimestamp(ts, tz).strftime("%H:%M") if ts else "-"
