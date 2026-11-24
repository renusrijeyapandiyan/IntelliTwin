# Status: what is finished, what was verified, what is not

"Verified" means it was actually executed in the build sandbox. Anything else is written but unexecuted.

## Finished and verified (executed here)

| Component | How it was verified |
|---|---|
| Simulator: healthy model, start-up transient, 5 faults, seed option, glitch mode, control API, CLI | `pytest`: 16 tests pass; dry-run CLI output inspected |
| AI service: feature extraction, `/predict`, auth, registry (versions, shadow, promote, rollback), chronological split | `pytest`: 11 tests pass |
| Training script: real metrics, threshold baseline, versioned artifacts | Run end to end; metrics printed and saved by the script (reproduce with the command in the README) |
| Self-learning loop: KS/PSI drift, feedback tracking, candidate training, shadow evaluation, no auto-promotion | `pytest`: 9 tests pass, including `cycle()` against an in-memory MongoDB (mongomock); `python -m learning.demo_drift` run end to end |
| Backend core logic (`com.intellitwin.core`): validation, features, twin update, decision engine, reasons | Compiled with `javac` 17 and run on the real JUnit 5.10 launcher: 31/31 tests pass. The Java feature extractor matches the Python one on the shared golden fixture to 1e-9 |
| Decision defaults (weights, thresholds, smoothing) | Chosen by replaying simulated faults through the trained model and the Java decision engine (multiple faults, severities and 8 seeds for the demo scenario) |
| Frontend | `vite build` succeeds (including the new audit-log page) |
| `docker-compose.yml` | Parsed as valid YAML with the expected services/volumes |

## Written but NOT executed here (please run before relying on it)

* **Spring Boot layer** (security/JWT filters, controllers, `IngestService`, MongoDB persistence, alerts, model governance,
  seeding, proxy to the simulator). It was never compiled or started: this sandbox has no Maven Central access. A
  parse-only compile showed no syntax errors, but type errors, wiring mistakes or mapping issues are possible.
  First thing to run: `cd backend && mvn test` then `mvn spring-boot:run`. 
* **Docker images and the full compose stack** (no Docker in the sandbox), including the first-start model training.
* **Dashboard in a browser** against a live backend (it builds, but no page was exercised).
* **`scripts/demo.py` / `demo.sh`** end to end. The CONTINUE → SCHEDULE path was checked by replaying the
  simulator through the real model and the Java decision engine, not through the running stack.

## Known limitations

* All metrics come from **simulated** data whose faults are well separated; they overstate what real equipment would give.
  The "threshold baseline" is a simple fixed-rule alarm, not a tuned industrial system.
* Per-second Random Forest probabilities are noisy while a fault ramps up. The smoothing and hysteresis reduce flapping but
  do not remove it: occasional brief IMMEDIATE blips or step-backs occur (2 of 8 replay seeds each).
* The thresholds were tuned on the simulator and are configuration, not truth.
* Retraining labels come from maintenance feedback (weak, sparse) plus "quiet" windows assumed healthy. Shadow promotion
  readiness needs >= 200 labelled windows with both classes; on the live stack that takes real maintenance records.
  `learning.demo_drift` uses simulator ground truth in place of feedback.
* The backend keeps per-machine rolling windows in memory (rebuilt after restart), so it is designed as a single instance.
  Ingestion calls the AI service synchronously (2 s timeout; on failure the twin is updated with status `AI_UNAVAILABLE`).
* Login throttling is in memory; the JWT is held in `sessionStorage`; there is no refresh-token flow, TLS, or MongoDB auth in the compose file.
* The risk-band thresholds drawn on the dashboard are a copy of the backend defaults; changing `application.yml` does not change the drawing.
* No WebSocket push: the dashboard polls every 2 s.

## Not done

* Password change / reset for existing users (admins can create and delete accounts, not edit them).
* Automated tests for the React code and Spring controllers/integration (only core logic has JUnit tests).
* Multi-instance backend, message queue, or time-series database.
