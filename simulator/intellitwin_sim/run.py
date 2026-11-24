"""Entry point:  python -m intellitwin_sim.run --backend http://localhost:8080 --seed 42"""
import argparse
import logging
import os
import signal
import sys
import time

import uvicorn

from .client import IngestClient
from .control_api import create_app
from .machines import PROFILES
from .runner import Runner


def main(argv=None):
    ap = argparse.ArgumentParser(description="IntelliTwin sensor simulator")
    ap.add_argument("--backend", default=os.getenv("BACKEND_URL", "http://localhost:8080"))
    ap.add_argument("--api-key", default=os.getenv("INGEST_API_KEY", ""))
    ap.add_argument("--seed", type=int, default=int(os.getenv("SIM_SEED", "42")),
                    help="fixed random seed (use -1 for non-deterministic)")
    ap.add_argument("--interval", type=float, default=float(os.getenv("SIM_INTERVAL", "1.0")))
    ap.add_argument("--machines", default=os.getenv("SIM_MACHINES", ",".join(PROFILES)))
    ap.add_argument("--control-port", type=int, default=int(os.getenv("CONTROL_PORT", os.getenv("PORT", "8100"))))
    ap.add_argument("--glitches", action="store_true", help="inject missing/out-of-range values")
    ap.add_argument("--dry-run", action="store_true", help="print readings instead of sending")
    a = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    log = logging.getLogger("intellitwin.sim")

    if not a.dry_run and not a.api_key:
        sys.exit("INGEST_API_KEY is required (set the env var or pass --api-key)")

    if a.dry_run:
        def sink(p):
            print(p)
            return True
        client = None
    else:
        client = IngestClient(a.backend, a.api_key)
        sink = client.send

    seed = None if a.seed < 0 else a.seed
    runner = Runner(a.machines.split(","), seed, a.interval, sink, glitches=a.glitches)
    runner.start()
    log.info("simulator running: machines=%s seed=%s interval=%ss", a.machines, seed, a.interval)

    signal.signal(signal.SIGTERM, lambda *_: runner.stop())
    if a.dry_run:
        try:
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            runner.stop()
        return
    uvicorn.run(create_app(runner), host="0.0.0.0", port=a.control_port, log_level="warning")


if __name__ == "__main__":
    main()
