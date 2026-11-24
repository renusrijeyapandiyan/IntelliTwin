#!/usr/bin/env bash
# Generates .env with random secrets and random demo passwords. Never commit .env.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ] && [ "${1:-}" != "--force" ]; then echo ".env already exists (use --force to overwrite)"; exit 0; fi
rnd() { python3 -c "import secrets,sys; print(secrets.token_urlsafe(int(sys.argv[1])))" "$1"; }
ADMIN_PW=$(rnd 12); ENG_PW=$(rnd 12); OP_PW=$(rnd 12)
umask 077
cat > .env <<ENV
JWT_SECRET=$(rnd 48)
INGEST_API_KEY=$(rnd 24)
AI_API_KEY=$(rnd 24)
CONTROL_TOKEN=$(rnd 16)
SEED_ADMIN_PASSWORD=$ADMIN_PW
SEED_ENGINEER_PASSWORD=$ENG_PW
SEED_OPERATOR_PASSWORD=$OP_PW
SIM_SEED=42
ENV
echo "Created .env. Demo logins (also stored in .env, change them for anything beyond a demo):"
echo "  admin    / $ADMIN_PW"
echo "  engineer / $ENG_PW"
echo "  operator / $OP_PW"
