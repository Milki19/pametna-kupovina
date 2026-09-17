#!/usr/bin/env bash
# Na serveru: pravi sliku backend-a iz release/backend.jar i pokreće sve servise.
set -euo pipefail
. "$(dirname "$0")/env.sh"

compose up -d --build --wait
# Every release leaves the previous backend image behind without a name.
docker image prune --force --filter label=rs.pametnakupovina.image=backend
compose ps
