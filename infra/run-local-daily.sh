#!/usr/bin/env bash
# Run from any directory: bash /path/to/pametna-kupovina/infra/run-local-daily.sh
set -euo pipefail
pk_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
test -n "$pk_script_dir"
pk_project_dir="$(cd -- "$pk_script_dir/.." && pwd -P)"
test -n "$pk_project_dir"
test -f "$pk_project_dir/pametna-kupovina-backend/pom.xml"
cd -- "$pk_project_dir/pametna-kupovina-backend"
test -s target/backend-0.0.1-SNAPSHOT.jar || { echo 'Prvo napravi testiran backend build.'; exit 1; }
if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null; then
    echo 'Port 8080 je zauzet. Postojeći server nije zaustavljen.'
    exit 1
fi
# Never run directly from Maven's target: a later build rewrites that archive
# while the JVM can still be loading classes from it.
pk_jar_hash="$(shasum -a 256 target/backend-0.0.1-SNAPSHOT.jar | awk '{print $1}')"
[[ "$pk_jar_hash" =~ ^[0-9a-f]{64}$ ]] || exit 1
mkdir -p -- "$pk_project_dir/infra/runtime"
pk_runtime_jar="$pk_project_dir/infra/runtime/backend-$pk_jar_hash.jar"
if test ! -e "$pk_runtime_jar"; then
    cp -n target/backend-0.0.1-SNAPSHOT.jar "$pk_runtime_jar"
fi
pk_copied_hash="$(shasum -a 256 "$pk_runtime_jar" | awk '{print $1}')"
test "$pk_copied_hash" = "$pk_jar_hash"
pk_db_user="$(docker exec pametna-kupovina-postgres printenv POSTGRES_USER)"
pk_db_password="$(docker exec pametna-kupovina-postgres printenv POSTGRES_PASSWORD)"
pk_db_name="$(docker exec pametna-kupovina-postgres printenv POSTGRES_DB)"
test -n "$pk_db_user"
test -n "$pk_db_password"
test "$pk_db_name" = pametna_kupovina
# Loopback unless a phone on the same network has to reach the API. Binding
# wider also exposes the administrative import endpoints, so set an admin key
# when opening this up: PK_BIND_ADDRESS=0.0.0.0 PK_ADMIN_API_KEY=... run this.
pk_bind_address="${PK_BIND_ADDRESS:-127.0.0.1}"
[[ "$pk_bind_address" =~ ^[0-9.]+$ ]] || exit 1

# Passed through the environment, never on the command line, so the key does
# not show up in the process list.
if [[ -n "${PK_ADMIN_API_KEY:-}" ]]; then
    export ADMIN_API_KEY_REQUIRED=true
    export ADMIN_API_KEY="$PK_ADMIN_API_KEY"
elif [[ "$pk_bind_address" != "127.0.0.1" ]]; then
    echo 'Upozorenje: API je otvoren ka mreži bez administratorskog ključa.'
    echo 'Postavi PK_ADMIN_API_KEY da uvoz i izvori ne budu svima dostupni.'
fi

export DB_URL=jdbc:postgresql://127.0.0.1:5432/pametna_kupovina
export DB_USERNAME="$pk_db_user"
export DB_PASSWORD="$pk_db_password"
exec java -Xmx2048m -Dspring.devtools.restart.enabled=false \
    -jar "$pk_runtime_jar" \
    --spring.profiles.active=local-daily \
    --server.address="$pk_bind_address" \
    --price-import.archive.directory="$pk_project_dir/infra/import-archive" \
    --price-import.worker.enabled=false \
    --verified-location-import.schedule.enabled=false
