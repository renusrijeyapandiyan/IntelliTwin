# Deploying IntelliTwin on Render (demo setup)

Services created by `render.yaml`: `intellitwin-web` (static dashboard), `intellitwin-api` (Spring Boot),
`intellitwin-ai` (FastAPI + model), `intellitwin-sim` (sensor simulator). The database is MongoDB Atlas (Render has no
managed MongoDB). The self-learning loop is NOT deployed: it needs a disk shared with the AI service, which Render only
offers on paid plans, so the Models page works but drift/shadow/promotion stay idle.

Free-plan facts (Render docs): 512 MB RAM per service, sleeps after 15 minutes without inbound traffic (about 1 minute to
wake), ephemeral disk, 750 free instance hours per month for the whole workspace. Three always-on free services use about
2,160 hours a month, so they run out after roughly 10 days. Fine for a demo or viva; use paid plans for permanent hosting.

Steps: see the chat answer / the commands below.
1. Push to GitHub (git init, add, commit, remote add, push).
2. MongoDB Atlas: free M0 cluster, database user, Network Access 0.0.0.0/0, copy the connection string and add `/intellitwin`.
3. Render dashboard > Env Groups > New: name `intellitwin-secrets`, keys JWT_SECRET, INGEST_API_KEY, AI_API_KEY,
   CONTROL_TOKEN, SEED_ADMIN_PASSWORD, SEED_ENGINEER_PASSWORD, SEED_OPERATOR_PASSWORD, MONGO_URI.
4. Render > New > Blueprint > pick the repository > Apply.
5. Open https://intellitwin-web.onrender.com (names must match the URLs written in `render.yaml`).
6. Keep the simulator awake with a free uptime monitor on https://intellitwin-sim.onrender.com/health (every 5 min).
