"""Versioned model registry on disk.

model_registry/
  registry.json              {"active","previous","shadow","versions":{vN:{status,...}}}
  v0001/model.joblib  meta.json  metrics.json
"""
from __future__ import annotations
import json
import os
import re
import tempfile
import threading
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

import joblib

_LOCK = threading.RLock()
STATUSES = ("ACTIVE", "CANDIDATE", "SHADOW", "ARCHIVED", "ROLLED_BACK")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _atomic_write(path: Path, text: str):
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.replace(tmp, path)


try:                                   # cross-process file lock (AI service + learning loop share the registry volume)
    import fcntl
except ImportError:                    # pragma: no cover (non-POSIX)
    fcntl = None


class Registry:
    def __init__(self, root: str | os.PathLike):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.file = self.root / "registry.json"
        if not self.file.exists():
            self._save({"active": None, "previous": None, "shadow": None, "versions": {}})

    @contextmanager
    def _txn(self):
        """Thread lock + advisory file lock so two processes never interleave registry writes."""
        with _LOCK:
            if fcntl is None:
                yield
                return
            with open(self.root / "registry.lock", "w") as lf:
                fcntl.flock(lf, fcntl.LOCK_EX)
                try:
                    yield
                finally:
                    fcntl.flock(lf, fcntl.LOCK_UN)

    # ---------- state ----------
    def _load(self) -> Dict[str, Any]:
        return json.loads(self.file.read_text())

    def _save(self, state: Dict[str, Any]):
        _atomic_write(self.file, json.dumps(state, indent=2))

    def mtime(self) -> float:
        return self.file.stat().st_mtime

    def state(self) -> Dict[str, Any]:
        with _LOCK:
            return self._load()

    @property
    def active(self) -> Optional[str]:
        return self.state()["active"]

    def next_version(self) -> str:
        with _LOCK:
            nums = [int(m.group(1)) for v in self._load()["versions"] if (m := re.fullmatch(r"v(\d{4})", v))]
            return f"v{(max(nums) + 1 if nums else 1):04d}"

    # ---------- artifacts ----------
    def save_version(self, bundle: Dict[str, Any], meta: Dict[str, Any], metrics: Dict[str, Any],
                     activate: bool = False, parent: Optional[str] = None) -> str:
        with self._txn():
            version = self.next_version()
            d = self.root / version
            d.mkdir(parents=True)
            joblib.dump(bundle, d / "model.joblib")
            meta = {**meta, "version": version, "createdAt": _now(), "parent": parent}
            (d / "meta.json").write_text(json.dumps(meta, indent=2))
            (d / "metrics.json").write_text(json.dumps(metrics, indent=2))
            st = self._load()
            st["versions"][version] = {"status": "CANDIDATE", "createdAt": meta["createdAt"], "parent": parent}
            self._save(st)
        if activate or self.active is None:
            self.set_active(version)
        return version

    def load_bundle(self, version: str) -> Dict[str, Any]:
        return joblib.load(self.root / version / "model.joblib")

    def read_json(self, version: str, name: str) -> Dict[str, Any]:
        return json.loads((self.root / version / name).read_text())

    def list_versions(self) -> List[Dict[str, Any]]:
        st = self.state()
        out = []
        for v, info in sorted(st["versions"].items()):
            row = {"version": v, **info}
            for name in ("meta", "metrics"):
                p = self.root / v / f"{name}.json"
                row[name] = json.loads(p.read_text()) if p.exists() else {}
            out.append(row)
        return out

    # ---------- lifecycle ----------
    def set_active(self, version: str):
        with self._txn():
            st = self._load()
            if version not in st["versions"]:
                raise KeyError(version)
            old = st["active"]
            if old == version:
                return
            if old:
                st["versions"][old]["status"] = "ARCHIVED"
            st["previous"] = old
            st["active"] = version
            st["versions"][version].update(status="ACTIVE", promotedAt=_now())
            if st["shadow"] == version:
                st["shadow"] = None
            self._save(st)

    def rollback(self) -> str:
        with self._txn():
            st = self._load()
            prev, cur = st["previous"], st["active"]
            if not prev:
                raise ValueError("no previous version to roll back to")
            st["versions"][cur]["status"] = "ROLLED_BACK"
            st["versions"][prev].update(status="ACTIVE", promotedAt=_now())
            st["active"], st["previous"] = prev, None
            self._save(st)
            return prev

    def set_shadow(self, version: Optional[str]):
        with self._txn():
            st = self._load()
            if st["shadow"]:
                if st["versions"].get(st["shadow"], {}).get("status") == "SHADOW":
                    st["versions"][st["shadow"]]["status"] = "CANDIDATE"
            if version:
                if version not in st["versions"] or version == st["active"]:
                    raise KeyError(f"cannot shadow {version}")
                st["versions"][version]["status"] = "SHADOW"
            st["shadow"] = version
            self._save(st)

    def annotate(self, version: str, **fields):
        with self._txn():
            st = self._load()
            st["versions"][version].update(fields)
            self._save(st)
