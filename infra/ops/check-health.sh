#!/usr/bin/env bash
# Na serveru iz cron-a, na svakih 5 minuta: da li server odgovara preko
# HTTPS-a, da li su svi kontejneri živi i zdravi, da li ima mesta na disku i
# slobodne memorije, da li je poslednji backup svež i da li su stigli novi
# izveštaji o padu aplikacije. Poruka na ALERT_URL ide
# samo kad se spisak problema promeni (i kad sve ponovo bude u redu), a ne na
# svakih 5 minuta. Uz HEALTH_PING_URL javlja se i spoljnom servisu, koji
# obaveštava kad se server potpuno ugasi.
set -euo pipefail
. "$(dirname "$0")/env.sh"

domain="$(env_value APP_DOMAIN)"
https_port="$(env_value HTTPS_PORT)"
alert_url="$(env_value ALERT_URL)"
ping_url="$(env_value HEALTH_PING_URL)"
disk_limit="$(env_value HEALTH_DISK_PERCENT)"
memory_limit="$(env_value HEALTH_MEMORY_FREE_PERCENT)"
backup_hours="$(env_value HEALTH_BACKUP_MAX_HOURS)"
health_url="https://${domain}${https_port:+:$https_port}/actuator/health"
insecure=""
if [ "$domain" = "localhost" ]; then insecure="--insecure"; fi

problems=()

if ! curl --silent --max-time 20 $insecure "$health_url" | grep -q '"status":"UP"'; then
    problems+=("server ne odgovara na $health_url")
fi

for service in postgres backend caddy; do
    state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' \
        "$(compose ps -q "$service" 2>/dev/null || true)" 2>/dev/null || echo "nema")"
    case "$state" in
        "running "|"running healthy"|"running starting") ;;
        *) problems+=("kontejner $service: ${state% }") ;;
    esac
done

disk_used="$(df -P . | awk 'NR == 2 { sub("%", "", $5); print $5 }')"
if [ "$disk_used" -ge "${disk_limit:-85}" ]; then
    problems+=("disk je popunjen preko ${disk_limit:-85}%")
fi

memory_free="$(awk '/^MemTotal:/ { total = $2 } /^MemAvailable:/ { free = $2 } END { printf "%d", free * 100 / total }' /proc/meminfo)"
if [ "$memory_free" -lt "${memory_limit:-10}" ]; then
    problems+=("slobodne memorije je manje od ${memory_limit:-10}%")
fi

newest_backup="$(ls -1t backups/pametna-kupovina-*.dump 2>/dev/null | head -n 1 || true)"
if [ -z "$newest_backup" ]; then
    problems+=("nema nijednog backup-a u backups/")
elif [ -z "$(find "$newest_backup" -mmin -"$(( ${backup_hours:-36} * 60 ))")" ]; then
    problems+=("poslednji backup je stariji od ${backup_hours:-36} h")
fi

report=""
if [ "${#problems[@]}" -gt 0 ]; then
    report="$(printf '%s; ' "${problems[@]}")"
    report="${report%; }"
fi

state_file="backups/.health-state"
previous="$(cat "$state_file" 2>/dev/null || true)"
printf '%s' "$report" > "$state_file"

if [ -z "$report" ]; then
    echo "U redu."
    message="Pametna kupovina: ponovo sve u redu."
else
    echo "$report" >&2
    message="Pametna kupovina: $report"
fi

if [ "$report" != "$previous" ] && [ -n "$alert_url" ]; then
    curl --fail --silent --show-error --max-time 20 --data "$message" "$alert_url" >/dev/null
fi

# Novi izveštaji o padu Android aplikacije (šalje ih sama aplikacija, bez
# tuđeg alata): jedna poruka kad stignu, detalji su na /admin.
if [ -n "$alert_url" ]; then
    newest_crash="$(compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "SELECT COALESCE(MAX(id), 0) FROM app.crash_report"' 2>/dev/null || true)"
    seen_crash="$(cat backups/.crash-seen 2>/dev/null || true)"
    if [ -n "$newest_crash" ]; then
        printf '%s' "$newest_crash" > backups/.crash-seen
        if [ -n "$seen_crash" ] && [ "$newest_crash" -gt "$seen_crash" ]; then
            curl --fail --silent --show-error --max-time 20 \
                --data "Pametna kupovina: stiglo je $(( newest_crash - seen_crash )) novih izveštaja o padu aplikacije (vidi /admin)." \
                "$alert_url" >/dev/null || true
        fi
    fi
fi

# Spolja (healthchecks.io): ping znači samo „server je živ i cron radi".
# Probleme iznad već javlja ALERT_URL; ako pingovi izostanu, server je pao
# toliko da ni ova skripta ne radi, pa poruku šalje taj servis.
if [ -n "$ping_url" ]; then
    curl --silent --max-time 10 --retry 2 "$ping_url" >/dev/null || true
fi

[ -z "$report" ]
