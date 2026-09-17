#!/usr/bin/env bash
# Sa Mac-a: šalje server postavku i testiran jar na server i pokreće je.
# .env.production na serveru se ne dira.
# usage: infra/ops/deploy.sh ubuntu@IP-servera [folder na serveru]
set -euo pipefail
target="${1:?Navedi server, npr. ubuntu@123.45.67.89}"
remote_dir="${2:-pametna-kupovina}"
infra="$(cd "$(dirname "$0")/.." && pwd -P)"

test -s "$infra/release/backend.jar" || { echo "Prvo pokreni infra/ops/release-backend.sh." >&2; exit 1; }

ssh "$target" "mkdir -p '$remote_dir/ops' '$remote_dir/release' '$remote_dir/backups'"
rsync -az \
    "$infra/compose.production.yaml" \
    "$infra/Caddyfile" \
    "$infra/backend.Dockerfile" \
    "$infra/.dockerignore" \
    "$infra/.env.production.example" \
    "$target:$remote_dir/"
rsync -az "$infra/ops/" "$target:$remote_dir/ops/"
rsync -az "$infra/release/backend.jar" "$target:$remote_dir/release/"

# The first time there are no settings yet: they are written on the server,
# and ops/restore.sh brings the database over and starts everything.
ssh "$target" "cd '$remote_dir' && chmod +x ops/*.sh && if [ -f .env.production ]; then ops/start.sh; else echo 'Poslato. Na serveru napravi .env.production iz .env.production.example, pa pokreni ops/restore.sh ili ops/start.sh.'; fi"
