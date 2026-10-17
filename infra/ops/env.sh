# Shared by the ops scripts: runs from the server folder that holds
# compose.production.yaml and .env.production.

cd "$(dirname "$0")/.." || exit 1
test -f .env.production || { echo "Nema .env.production (napravi ga iz .env.production.example)." >&2; exit 1; }

# The last value of a setting in .env.production, without quotes. The file is
# not sourced: values such as BACKEND_JAVA_OPTIONS contain spaces.
env_value() {
    local line
    line="$(grep -E "^$1=" .env.production | tail -n 1 || true)"
    line="${line#*=}"
    line="${line%\"}"
    printf '%s' "${line#\"}"
}

compose() {
    docker compose --env-file .env.production -f compose.production.yaml "$@"
}

# Starts the backend (and the database it needs) and waits until it answers.
# Not `compose up --wait`: a release with long Flyway migrations keeps the
# backend unready for many minutes, and Compose then either gave up or, on the
# server, kept "Waiting" after the backend was already healthy. This loop
# reads the container's own health, shows how far the migration got, and
# stops only on success, a crash or BACKEND_WAIT_MINUTES (default 45).
start_backend() {
    local limit="${BACKEND_WAIT_MINUTES:-45}" started container state restarts migration shown=""
    started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    compose up -d --build backend
    container="$(compose ps -q backend)"
    restarts="$(docker inspect --format '{{.RestartCount}}' "$container")"
    for (( second = 0; ; second += 10 )); do
        state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}} {{.RestartCount}}' "$container")"
        case "$state" in
            "running healthy "*)
                echo "Backend je zdrav posle $(( second / 60 )) min $(( second % 60 )) s."
                return 0 ;;
            "running starting $restarts"|"running unhealthy $restarts") ;;
            *)
                echo "Backend se nije pokrenuo (stanje: $state). Poslednje linije loga:" >&2
                compose logs --tail 40 backend >&2
                return 1 ;;
        esac
        if (( second >= limit * 60 )); then
            echo "Backend nije zdrav ni posle $limit min. Poslednje linije loga:" >&2
            compose logs --tail 40 backend >&2
            return 1
        fi
        migration="$(compose logs --no-log-prefix --since "$started" backend 2>/dev/null \
            | grep -o 'Migrating schema .* to version "[^"]*"' | tail -n 1 || true)"
        if (( second % 60 == 0 )) || [ "$migration" != "$shown" ]; then
            echo "Čeka se backend: $(( second / 60 )) min${migration:+, ${migration#Migrating schema * to }}"
            shown="$migration"
        fi
        sleep 10
    done
}
