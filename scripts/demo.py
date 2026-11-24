#!/usr/bin/env python3
"""IntelliTwin demo: inject a bearing-wear fault and watch the recommendation escalate.

    ./scripts/demo.sh            # starts the stack, then runs this
    python3 scripts/demo.py      # stack already running

Uses only the standard library. Credentials are read from .env (created by scripts/make-env.sh).
It prints what it OBSERVES; nothing is simulated by this script itself.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ORDER = ["CONTINUE_MONITORING", "INSPECT_MACHINE", "SCHEDULE_MAINTENANCE", "IMMEDIATE_INTERVENTION"]


def load_env(path):
    env = {}
    for line in Path(path).read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def call(base, path, token=None, method="GET", body=None):
    req = urllib.request.Request(base + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            txt = r.read().decode()
            return json.loads(txt) if txt else None
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} on {method} {path}: {e.read().decode()[:200]}")


def wait(cond, what, timeout, every=3):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            v = cond()
            if v:
                return v
        except Exception:
            pass
        time.sleep(every)
    sys.exit(f"timed out waiting for {what}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:3000", help="frontend (nginx) or backend URL")
    ap.add_argument("--env", default=str(Path(__file__).resolve().parent.parent / ".env"))
    ap.add_argument("--machine", default="M-001")
    ap.add_argument("--fault", default="bearing_wear")
    ap.add_argument("--severity", type=float, default=0.4)
    ap.add_argument("--ramp", type=float, default=150)
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--keep", action="store_true", help="do not clear the fault at the end")
    a = ap.parse_args()
    env = load_env(a.env)

    print("== 1. waiting for the stack to answer ==")
    wait(lambda: call(a.base, "/api/auth/login", method="POST", body={"username": "engineer", "password": env["SEED_ENGINEER_PASSWORD"]}), "login", 300)
    token = call(a.base, "/api/auth/login", method="POST", body={"username": "engineer", "password": env["SEED_ENGINEER_PASSWORD"]})["token"]
    print("   logged in as engineer")

    print(f"== 2. waiting for {a.machine} to finish warm-up (the twin needs ~2.5 min of stable running data) ==")
    twin = wait(lambda: (lambda t: t if t["aiStatus"] == "OK" else None)(call(a.base, f"/api/twins/{a.machine}", token)), "first AI assessment", 600, 5)
    print(f"   baseline: {twin['recommendation']}  risk={twin['riskScore']:.2f}  p(fail)={twin['failureProbability']:.2f}  "
          f"model={twin['modelVersion']}  sensor->twin latency={twin['latencyMs']:.0f} ms")
    if twin["recommendation"] != "CONTINUE_MONITORING":
        print("   (note: machine is not at CONTINUE_MONITORING at the start; clearing any faults first)")
        call(a.base, f"/api/sim/faults/{a.machine}", token, "DELETE")
        time.sleep(60)

    print(f"== 3. injecting {a.fault} on {a.machine} (ramp {a.ramp:.0f} s, final severity {a.severity}) ==")
    call(a.base, "/api/sim/faults", token, "POST", {"machineId": a.machine, "fault": a.fault, "rampSeconds": a.ramp, "severity": a.severity})
    t0 = time.time()
    seen, last_rec = [], None
    print(f"   {'t(s)':>5}  {'severity':>8}  {'risk':>5}  {'p(fail)':>7}  recommendation")
    while time.time() - t0 < a.timeout:
        t = call(a.base, f"/api/twins/{a.machine}", token)
        st = call(a.base, "/api/sim/status", token)["machines"][a.machine]["faults"].get(a.fault, 0.0)
        rec = t["recommendation"] if t["aiStatus"] == "OK" else "(not assessed)"
        if rec != last_rec or int(time.time() - t0) % 30 < 5:
            print(f"   {time.time() - t0:5.0f}  {st:8.2f}  {t['riskScore'] or 0:5.2f}  {t['failureProbability'] or 0:7.2f}  {rec}"
                  + ("  [low confidence: review]" if t["lowConfidence"] else ""))
        if rec != last_rec and rec in ORDER:
            seen.append(rec); last_rec = rec
        if rec == "SCHEDULE_MAINTENANCE" and st >= a.severity * 0.99:
            print("\n   reasons given by the decision engine:")
            for r in t["reasons"]:
                print("    -", r)
            break
        time.sleep(5)
    print(f"\n== 4. recommendation path observed: {' -> '.join(seen) or 'none'} ==")
    alerts = call(a.base, f"/api/alerts?machineId={a.machine}&limit=10", token)
    print(f"   alerts raised: {len(alerts)}; newest: {alerts[0]['message'] if alerts else '-'}")
    if not a.keep:
        call(a.base, f"/api/sim/faults/{a.machine}", token, "DELETE")
        print("   fault cleared; the risk will fall back over the next minutes")
    if "SCHEDULE_MAINTENANCE" not in seen:
        print("   NOTE: SCHEDULE_MAINTENANCE was not reached within the timeout; see README 'Known limitations'.")


if __name__ == "__main__":
    main()
