#!/usr/bin/env bash
# Na serveru: pravi sliku backend-a iz release/backend.jar i pokreće sve servise.
# Duge migracije baze su u redu: čeka se dok backend ne postane zdrav.
set -euo pipefail
. "$(dirname "$0")/env.sh"

# The name-meaning model (V135), once; the backend starts without it too.
"$(dirname "$0")/name-model.sh" || echo "Model za značenje naziva nije preuzet; predlozi se boduju samo po slovima."
start_backend
# The backend is healthy by now, so Caddy's depends_on is met at once.
compose up -d
# Caddy keeps running across releases; this picks up a changed Caddyfile.
compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile
# Every release leaves the previous backend image behind without a name.
docker image prune --force --filter label=rs.pametnakupovina.image=backend
compose ps
