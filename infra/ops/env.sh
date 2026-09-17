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
