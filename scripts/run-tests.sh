#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "== simulator ==";  (cd simulator  && python3 -m pytest -q)
echo "== ai-service =="; (cd ai-service && python3 -m pytest -q)
if command -v mvn >/dev/null; then echo "== backend =="; (cd backend && mvn -q test); else echo "== backend: mvn not found, skipped =="; fi
