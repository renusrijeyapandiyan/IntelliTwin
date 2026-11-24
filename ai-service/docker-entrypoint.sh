#!/bin/sh
# Trains the first model if the registry is empty, then serves.
set -e
if ! python -c "import sys; from app.registry import Registry; sys.exit(0 if Registry('${MODEL_REGISTRY_DIR}').active else 1)"; then
  echo "[ai-service] no active model found: training v0001 from the simulator (one-off, ~1 min)"
  python -m training.train --seed "${TRAIN_SEED:-42}" --cycles "${TRAIN_CYCLES:-24}" --registry "${MODEL_REGISTRY_DIR}" --activate
fi
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8000}"
