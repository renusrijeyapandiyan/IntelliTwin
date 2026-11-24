# IntelliTwin: a self-learning digital twin for maintenance decision support

IntelliTwin keeps a **digital twin** of each machine, scores its health with machine-learning models, and
**recommends** a maintenance action (continue monitoring, inspect, schedule maintenance, immediate intervention).
It is **advisory only**: no code path controls a machine. There is **no hardware**: a built-in simulator produces
the sensor data, including injectable faults.

```
 simulator (Python) ──POST /api/sensors/ingest (X-API-Key)──►  backend (Spring Boot, JWT, RBAC)
   control API :8100 ◄── /api/sim/* proxy ──────────────────      │  validate → dedup → window features
                                                                   │  → POST /predict ─► ai-service (FastAPI)
 frontend (React, nginx :3000) ── /api ──────────────────────────►│  → decision engine → twin D(t+1)=F(D(t),X(t))
                                                                   │  → alerts, predictions log, audit log
                                                          MongoDB ◄┘
 learning loop (Python): drift (KS/PSI) + maintenance feedback → candidate → SHADOW → ADMIN promotes → rollback
```

## Repository layout

| Path | Contents |
|---|---|
| `simulator/` | Seeded sensor generator (healthy + 5 faults), HTTP client, control API, pytest |
| `ai-service/` | FastAPI `/predict`, feature spec, model registry, training script, learning loop, pytest |
| `backend/` | Spring Boot 3 (Java 17): REST API, JWT/RBAC, ingestion, twin, decision engine, JUnit |
| `frontend/` | React + Vite + Tailwind + React Router + Recharts dashboard |
| `shared/feature_golden.json` | Fixture that both the Python and Java feature extractors must reproduce |
| `scripts/` | `make-env.sh`, `demo.sh`, `demo.py`, `run-tests.sh` |
| `docker-compose.yml` | mongo, ai-service, backend, simulator, learning, frontend |
| `STATUS.md` | What is finished, what was verified, what is not |

## Run with Docker (recommended)

Requires Docker with Compose v2.

```bash
./scripts/make-env.sh          # writes .env with random secrets and random demo passwords (prints them once)
docker compose up --build -d   # first start trains model v0001 from the simulator (~1-2 min) and runs the Java tests
open http://localhost:3000     # log in as admin / engineer / operator with the passwords from .env
```

Wait about **2.5 minutes** after start: a machine must be `RUNNING` for 120 consecutive readings (thermal
warm-up) before the model is consulted. Until then cards show "Warming up".

Stop: `docker compose down` (add `-v` to also delete the database and the model registry).

## Run without Docker

You need Python 3.11+, Node 20+, JDK 17+, Maven, and a MongoDB (local or Atlas).

```bash
export MONGO_URI=mongodb://localhost:27017/intellitwin
export JWT_SECRET=$(python3 -c "import secrets;print(secrets.token_urlsafe(48))")
export INGEST_API_KEY=$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")
export AI_API_KEY=$(python3 -c "import secrets;print(secrets.token_urlsafe(24))")
export SEED_ADMIN_PASSWORD=...  SEED_ENGINEER_PASSWORD=...  SEED_OPERATOR_PASSWORD=...   # min 8 chars each

# 1. AI service (train once, then serve)
cd ai-service && pip install -r requirements.txt -r ../simulator/requirements.txt
python -m training.train --activate            # prints real precision / recall / F1 / ROC-AUC
uvicorn app.main:app --port 8000 &

# 2. Backend
cd ../backend && mvn spring-boot:run &

# 3. Simulator (needs INGEST_API_KEY; seed is fixed, --seed -1 for non-deterministic)
cd ../simulator && pip install -r requirements.txt && python -m intellitwin_sim.run --seed 42 &

# 4. Dashboard (dev server proxies /api to :8080)
cd ../frontend && npm install && npm run dev   # http://localhost:5173

# 5. Optional: self-learning loop
cd ../ai-service && python -m learning.loop
```

## Roles

| Role | Sees in the dashboard | Can do |
|---|---|---|
| OPERATOR | Overview, machine pages (recommendation, reasons, live sensors), Alerts, Maintenance history | acknowledge alerts (read-only otherwise) |
| ENGINEER | everything an operator sees, plus technical twin/timing panels, prediction log, Simulator, Models, Audit log | record maintenance, acknowledge alerts, inject simulator faults |
| ADMIN | everything, plus Users | create machines, promote / roll back models, manage user accounts |

Passwords are BCrypt-hashed. Users are seeded only from environment variables; nothing is hardcoded.
Failed logins are throttled (5 per 5 minutes per username).

## REST API

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /api/auth/login` | none | returns a JWT |
| `GET /api/machines`, `POST /api/machines` | any / ADMIN | list / create machines (with baselines) |
| `GET /api/twins`, `GET /api/twins/{machineId}` | any | digital twin state (the latter includes bounded history) |
| `POST /api/sensors/ingest` | `X-API-Key` | one reading; returns `ACCEPTED`, `DUPLICATE` or `OUT_OF_ORDER_IGNORED` |
| `GET /api/sensors/{machineId}?limit=&since=` | any | recent readings |
| `GET /api/predictions/{machineId}` | any | prediction audit log (model version, inference time, latency) |
| `GET /api/alerts`, `PATCH /api/alerts/{id}` | any | list / acknowledge |
| `GET /api/maintenance`, `POST /api/maintenance` | any / ADMIN, ENGINEER | maintenance history / record action + outcome |
| `GET /api/models` | ADMIN, ENGINEER | versions, metrics, drift report, shadow evaluation |
| `POST /api/models/{id}/promote`, `POST /api/models/rollback` | ADMIN | human approval gate; rollback |
| `GET /api/audit` | ADMIN, ENGINEER | audit trail |
| `GET/POST/DELETE /api/users` | ADMIN | list / create / delete accounts (hashes never returned) |
| `GET/POST/DELETE /api/sim/...` | any / ADMIN, ENGINEER | demo-only proxy to the simulator control API |

Ingest example (what the simulator sends):

```json
{"machineId":"M-001","ts":"2026-01-01T10:00:00.000Z","seq":1,"temperature":62.1,"vibration":2.0,
 "pressure":3.0,"current":35.2,"speed":1478.0,"state":"RUNNING"}
```

## How it works

**Simulator.** Per machine, 1 reading/second: temperature, vibration, pressure, current, speed, state
(`STARTUP`/`RUNNING`/`STOPPED`). Healthy behaviour has sensor noise, load changes with thermal lag and a
start-up transient (speed ramp, current inrush). Faults ramp up over a configurable time to a final severity:
`bearing_wear` (vibration, impulsive spikes, temperature), `jam` (current spike, speed drop), `leakage`
(pressure decay), `imbalance` (periodic vibration), `lube_loss` (temperature). Fixed seed: `--seed 42`.
`--glitches` adds missing / impossible values to exercise validation.

**Ingestion.** Schema and physical-range validation; missing values are forward-filled and flagged `SUSPECT`;
out-of-range values or too many gaps make the reading `BAD` (stored, excluded from windows). Duplicates
`(machineId, ts)` are rejected by a unique index; readings not newer than the twin are stored but ignored by the twin.
Features come from the last 30 `RUNNING` readings: mean, std, min, max, slope per sensor plus vibration RMS and crest
factor (27 features). The Python and Java implementations are checked against the same golden fixture.

**Twin.** `D(t+1) = F(D(t), X(t))` is a pure function (`TwinUpdater`). The twin holds latest values, health
indicators, anomaly score, failure probability, risk, recommendation, reasons, model version, `syncStatus`
(`SYNCED` or `STALE` after `TWIN_STALE_AFTER_SECONDS` without data), bounded history, and sensor-to-twin latency.

**Models.** Isolation Forest trained on healthy data gives an anomaly score (normalised 0-1); a Random Forest with
`class_weight="balanced"` gives failure probability. Features are divided by each machine's baseline so one model
serves all machines. Each prediction returns its top contributing features.

**Decision engine.** `R = (w1*p + w2*anomaly + w3*trend) / (w1+w2+w3)`, with `p` averaged over the last 5 predictions,
`trend` the fitted rise of `p` over the last 30, and R smoothed by an EMA, then mapped with hysteresis:

| Risk R | Recommendation |
|---|---|
| < 0.30 | CONTINUE_MONITORING |
| 0.30 – 0.50 | INSPECT_MACHINE |
| 0.50 – 0.75 | SCHEDULE_MAINTENANCE |
| >= 0.75 | IMMEDIATE_INTERVENTION |

Weights and thresholds live in `backend/src/main/resources/application.yml` (`intellitwin.decision.*`) and are
validated at start-up. Reasons are plain English ("vibration RMS 40% above baseline"). A result is flagged **low
confidence / needs human review** when `p` is near 0.5, the input data quality is not OK, or the two models disagree.
These defaults were chosen by replaying simulated faults through the trained model and the decision engine
(see `STATUS.md`); they are not universal.

**Self-learning loop** (`ai-service/learning`). Every `LEARN_INTERVAL_SECONDS` it reads recent predictions and
maintenance feedback from MongoDB and:

1. runs a KS test and PSI per feature of recent healthy-looking windows against the training reference
   (a feature drifts when KS p < 0.01 **and** PSI >= 0.2; drift = at least 20% of features);
2. tracks alarms against maintenance outcomes (`FAULT_CONFIRMED` / `NO_FAULT_FOUND`: missed faults and false alarms);
3. on drift or degraded feedback performance, trains a **candidate** on recent labelled data plus sampled historical
   data, compares it with the active model on a chronological holdout, and starts **shadow mode**;
4. in shadow mode the AI service scores every live window with the candidate too (stored, never used for decisions);
   the loop evaluates both on labelled shadow data and records whether the candidate matches or beats the active model.

**Promotion is never automatic.** An ADMIN approves it (button on the Models page, or):

```bash
TOKEN=$(curl -s localhost:3000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<from .env>"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -X POST localhost:3000/api/models/v0002/promote -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"note":"reviewed shadow results"}'
curl -X POST localhost:3000/api/models/rollback -H "Authorization: Bearer $TOKEN"     # back to the previous version
```

To see the whole loop without waiting for real drift or feedback, run the self-contained simulation (no database):

```bash
cd ai-service && python -m learning.demo_drift
```

It re-calibrates the simulated sensors, shows drift detection, trains a candidate, and prints active-vs-candidate
metrics computed on a holdout. Force a candidate on the live stack with `docker compose exec learning python -m learning.loop --once --force`.

## Metrics: reproduce, do not trust numbers from a document

```bash
cd ai-service && python -m training.train --seed 42 --cycles 24 --registry /tmp/reg
```

The script generates a labelled dataset from the simulator (label = injected fault severity >= 0.30), splits
**chronologically per machine** 60/20/20 with a purge gap so overlapping windows never straddle splits, picks the
decision threshold on validation only, and prints precision, recall, F1 and ROC-AUC on the held-out test set for the
Random Forest, the Isolation Forest and a fixed **threshold baseline** (alarm if temperature > 1.15x, vibration > 1.5x,
current > 1.3x baseline, or pressure < 0.8x, speed < 0.85x). The same numbers are saved in
`model_registry/<version>/metrics.json` and shown on the Models page. **They describe simulated data only**; the
simulator's faults are cleanly separable, so expect real equipment to be harder.

## Tests

```bash
./scripts/run-tests.sh          # or individually:
cd simulator  && python -m pytest -q
cd ai-service && python -m pytest -q
cd backend    && mvn test       # JUnit 5: validation, feature extraction (golden), twin update, decision mapping
```

## Configuration (environment variables)

| Variable | Purpose |
|---|---|
| `JWT_SECRET` (>= 32 chars), `INGEST_API_KEY`, `AI_API_KEY`, `CONTROL_TOKEN` | secrets; the backend refuses to start without the first three |
| `SEED_ADMIN_PASSWORD`, `SEED_ENGINEER_PASSWORD`, `SEED_OPERATOR_PASSWORD` | initial users (min 8 chars) |
| `MONGO_URI` | MongoDB or Atlas connection string |
| `TWIN_STALE_AFTER_SECONDS`, `TWIN_HISTORY_SIZE`, `TWIN_WARMUP_SAMPLES` | twin behaviour (warm-up must match `WARMUP_SAMPLES` in `ai-service/app/features.py`) |
| `SIM_SEED`, `SIM_INTERVAL` | simulator determinism and tick rate |
| `LEARN_INTERVAL_SECONDS`, `LEARN_LOOKBACK_HOURS` | learning loop cadence |

## Security notes

Secrets only via environment; passwords BCrypt (cost 12); stateless JWT; role checks on every endpoint; ingest is
protected by an API key compared in constant time; request bodies are validated; errors never leak stack traces;
containers run as non-root. For anything beyond a demo, add TLS in front of nginx, enable MongoDB authentication
(the compose MongoDB is reachable only on the internal network but has no password), and rotate the generated secrets.

See `STATUS.md` for known limitations.
