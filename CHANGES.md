# Changes in this revision

* **Security:** `/error` is now permitted in `SecurityConfig`. Spring Security 6 also protects the ERROR dispatch, so
  without this an unhandled 404/500 was reported to clients as 401.
* **Docker Compose:** the `learning` container reuses the AI image, whose HEALTHCHECK probes port 8000. The learning
  loop serves no HTTP, so it was always reported `unhealthy`; its healthcheck is now disabled.
* **Dashboard:** new **Audit log** page (`/audit`, ADMIN and ENGINEER only) over the existing `GET /api/audit`.
* **Docs:** `STATUS.md` updated. The 31 core Java tests were re-run on the real JUnit 5 launcher (all pass), and the
  simulator (16) and AI-service (20) pytest suites and `vite build` were re-run.

Not verifiable in the build sandbox (no Maven Central, no Docker): compiling and starting the Spring Boot layer and the
compose stack. Run `cd backend && mvn test`, then `docker compose up --build`.
* **Look and feel:** the dark/blue dashboard and login page were replaced by a soft light theme (cream background, sand
  sidebar, sage-green accent, warm status colours, rounded cards). All colours live in `frontend/tailwind.config.js`;
  chart colours are in `frontend/src/pages/MachineDetail.jsx`.
* **Roles now look different:** the sidebar, routes and machine page follow the user's role (operators no longer see
  Models, Simulator, Audit or the technical panels; the server already blocked those actions, the UI now matches).
* **Users page (ADMIN):** list, create and delete accounts via the new `UserController` (`/api/users`, ADMIN only).
  Like the rest of the Spring layer it was written but not compiled in the build sandbox.
* **Render deployment support:** `render.yaml` Blueprint, `DEPLOY_RENDER.md`; backend and AI service and simulator honour
  the `PORT` variable; the dashboard accepts `VITE_API_URL` for a separately hosted API (empty = same origin as before);
  the AI image now trains its first model at build time; `.gitattributes` keeps LF line endings; `.gitignore` extended.
