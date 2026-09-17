#!/usr/bin/env bash
# Na serveru, jednom dnevno iz cron-a: proveren dump baze u backups/, slanje
# najnovijeg dump-a van servera (BACKUP_UPLOAD_URL), brisanje dump-ova starijih
# od BACKUP_KEEP_DAYS dana i originalnih cenovnika starijih od
# IMPORT_ARCHIVE_KEEP_DAYS dana.
set -euo pipefail
. "$(dirname "$0")/env.sh"

keep_dumps="$(env_value BACKUP_KEEP_DAYS)"
keep_archive="$(env_value IMPORT_ARCHIVE_KEEP_DAYS)"
upload_url="$(env_value BACKUP_UPLOAD_URL)"

mkdir -p backups
# The dump is written as the user running this script, so it can be sent on.
compose --profile operations run --rm --user "$(id -u):$(id -g)" database-backup

newest="$(ls -1t backups/pametna-kupovina-*.dump | head -n 1)"
if [ -n "$upload_url" ]; then
    curl --fail --silent --show-error --max-time 600 \
        --upload-file "$newest" "${upload_url%/}/$(basename "$newest")"
    echo "Poslato van servera: $(basename "$newest")"
fi

find backups -name 'pametna-kupovina-*.dump' -type f -mtime +"${keep_dumps:-14}" -delete
compose exec -T backend find /data/import-archive -type f -mtime +"${keep_archive:-30}" -delete
echo "Backup gotov: $newest"
