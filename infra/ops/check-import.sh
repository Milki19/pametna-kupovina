#!/usr/bin/env bash
# Na serveru iz cron-a (npr. u 13:00 i 19:00): da li server odgovara preko
# HTTPS-a i da li je današnji dnevni uvoz cena uspeo. Ako nije, kratka poruka
# ide na ALERT_URL (npr. ntfy tema) i skripta završava sa greškom.
set -euo pipefail
. "$(dirname "$0")/env.sh"

domain="$(env_value APP_DOMAIN)"
https_port="$(env_value HTTPS_PORT)"
alert_url="$(env_value ALERT_URL)"
health_url="https://${domain}${https_port:+:$https_port}/actuator/health"
# localhost is only ever the rehearsal on the Mac, with Caddy's own certificate.
insecure=""
if [ "$domain" = "localhost" ]; then insecure="--insecure"; fi

problem=""
if ! curl --silent --max-time 20 $insecure "$health_url" | grep -q '"status":"UP"'; then
    problem="server ne odgovara na $health_url"
else
    statuses="$(compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "SELECT COALESCE(STRING_AGG(status, '"','"' ORDER BY id), '"'nije pokrenut'"') FROM app.price_refresh_cycle WHERE cycle_date = (NOW() AT TIME ZONE '"'Europe/Belgrade'"')::date"')"
    case ",$statuses," in
        *,SUCCEEDED,*|*,WARNING,*) ;;
        *) problem="dnevni uvoz cena nije uspeo ($statuses)" ;;
    esac
fi

if [ -z "$problem" ]; then
    echo "U redu."
    exit 0
fi

message="Pametna kupovina $(date +%d.%m.%Y.): $problem"
echo "$message" >&2
if [ -n "$alert_url" ]; then
    curl --fail --silent --show-error --max-time 20 --data "$message" "$alert_url" >/dev/null
fi
exit 1
