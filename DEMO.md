# Demo: bearing wear drives CONTINUE_MONITORING → SCHEDULE_MAINTENANCE

## One command

```bash
./scripts/demo.sh
```

It creates `.env` if needed, starts the stack, waits for the first model assessment of M-001, injects a
bearing-wear fault (ramp 150 s, final severity 0.4), prints the live risk and recommendation every few
seconds, prints the reasons, and clears the fault. It prints only what it observes.

## Manual walkthrough (dashboard)

1. `./scripts/make-env.sh && docker compose up --build -d`, open <http://localhost:3000>, log in as **engineer**.
2. **Overview**: after ~2.5 minutes all three cards read *Continue monitoring* with the risk marker in the green band.
3. **Simulator**: machine `M-001`, fault *Bearing wear*, ramp `150`, severity `0.4`, press *Inject fault*.
4. Open **M-001**. Watch vibration and temperature drift up in the live charts, the crest factor rise, the risk
   marker move right, and the recommendation change to *Inspect machine* and then *Schedule maintenance*,
   with reasons such as "vibration RMS … % above baseline". An alert appears on **Alerts**; acknowledge it.
5. **Maintenance**: record "Replaced bearing", outcome *Fault confirmed*. This is feedback for the learning loop.
6. **Simulator**: *Clear faults*. The risk falls back (it is smoothed, so it takes a few minutes).
7. Stale-twin banner: `docker compose stop simulator`. After ~10 s a purple banner appears and the cards show
   *Stale*. `docker compose start simulator` recovers it.
8. Severity `1.0` instead of `0.4` escalates to *Immediate intervention*, as does the `jam` fault.

## What to expect (and what was actually checked)

Replaying this exact scenario (M-001, bearing wear, ramp 150 s, severity 0.4, 8 simulator seeds) through the trained
model and the decision engine, all 8 runs ended in SCHEDULE_MAINTENANCE after passing through INSPECT_MACHINE. In 2 of
8 runs there was a brief IMMEDIATE_INTERVENTION blip on the way, and in 2 of 8 a temporary step back to
CONTINUE/INSPECT, because single-second probabilities from the Random Forest are noisy during the ramp. The recommendation path
is therefore *typically* but not *always* a clean staircase. This replay did not go through Docker.
