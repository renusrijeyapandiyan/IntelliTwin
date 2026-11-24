#!/usr/bin/env bash
# Starts everything and runs the bearing-wear demo.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] || ./scripts/make-env.sh
docker compose up -d --build
echo "Frontend: http://localhost:3000   (log in with the passwords in .env)"
python3 scripts/demo.py "$@"
