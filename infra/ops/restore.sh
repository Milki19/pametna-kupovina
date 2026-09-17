#!/usr/bin/env bash
# Na serveru: vraća dump u bazu. BRIŠE postojeću bazu na serveru. Služi za
# prvi prenos baze sa Mac-a i za probu vraćanja iz backup-a.
# usage: ops/restore.sh putanja/do/fajla.dump
set -euo pipefail

dump="${1:?Navedi .dump fajl}"
test -s "$dump" || { echo "Fajl ne postoji ili je prazan: $dump" >&2; exit 1; }
# env.sh changes into the server folder, so remember where the dump is first.
dump="$(cd "$(dirname "$dump")" && pwd -P)/$(basename "$dump")"
. "$(dirname "$0")/env.sh"

if [ "${PK_RESTORE_CONFIRMED:-}" != "DA" ]; then
    printf 'Ovo briše bazu na serveru i vraća %s. Upiši DA za nastavak: ' "$dump"
    read -r answer
    [ "$answer" = "DA" ] || { echo "Odustao."; exit 1; }
fi

compose stop backend
compose up -d --wait postgres
compose exec -T postgres sh -c 'dropdb -U "$POSTGRES_USER" --if-exists "$POSTGRES_DB" && createdb -U "$POSTGRES_USER" -T template0 "$POSTGRES_DB"'
compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner' < "$dump"
compose up -d --build --wait
echo "Baza vraćena iz $dump; backend je primenio nove migracije i radi."
