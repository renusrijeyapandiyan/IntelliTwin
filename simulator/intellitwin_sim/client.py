"""HTTP client that posts readings to the backend ingest endpoint."""
from __future__ import annotations
import logging
import time
from typing import Optional

import requests

log = logging.getLogger("intellitwin.sim")


class IngestClient:
    def __init__(self, base_url: str, api_key: str, timeout: float = 3.0):
        self.url = base_url.rstrip("/") + "/api/sensors/ingest"
        self.session = requests.Session()
        self.session.headers.update({"X-API-Key": api_key, "Content-Type": "application/json"})
        self.timeout = timeout
        self.sent = 0
        self.failed = 0
        self._last_warn = 0.0

    def send(self, payload: dict) -> bool:
        try:
            r = self.session.post(self.url, json=payload, timeout=self.timeout)
            if r.status_code < 300:
                self.sent += 1
                return True
            self._warn(f"backend returned {r.status_code}: {r.text[:120]}")
        except requests.RequestException as e:
            self._warn(f"backend unreachable: {e.__class__.__name__}")
        self.failed += 1
        return False

    def _warn(self, msg: str):
        now = time.time()
        if now - self._last_warn > 10:   # rate-limit log noise
            log.warning(msg)
            self._last_warn = now
