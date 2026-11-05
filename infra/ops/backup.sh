#!/usr/bin/env bash
# Na serveru, jednom dnevno iz cron-a: proveren dump baze u backups/, slanje
# najnovijeg dump-a van servera (BACKUP_UPLOAD_URL), brisanje dump-ova starijih
# od BACKUP_KEEP_DAYS dana i originalnih cenovnika starijih od
# IMPORT_ARCHIVE_KEEP_DAYS dana.
#
# Van servera ide samo šifrovan dump (age, javni ključ BACKUP_AGE_RECIPIENT).
# Privatni ključ nije na serveru, pa ni ko ima PAR link ni bucket ne može da
# pročita bazu. Dump-ovi u backups/ ostaju nešifrovani za brzo vraćanje: ko
# može da ih čita na serveru, može da čita i samu bazu.
set -euo pipefail
. "$(dirname "$0")/env.sh"

keep_dumps="$(env_value BACKUP_KEEP_DAYS)"
keep_archive="$(env_value IMPORT_ARCHIVE_KEEP_DAYS)"
upload_url="$(env_value BACKUP_UPLOAD_URL)"
recipient="$(env_value BACKUP_AGE_RECIPIENT)"
upload_refused=""

if [ -n "$upload_url" ]; then
    if [ -z "$recipient" ]; then
        upload_refused="BACKUP_AGE_RECIPIENT nije postavljen: dump se ne šalje van servera nešifrovan."
    elif ! command -v age >/dev/null 2>&1; then
        upload_refused="age nije instaliran (sudo apt install age): dump se ne šalje van servera."
    fi
fi

mkdir -p backups
# The dump is written as the user running this script, so it can be sent on.
compose --profile operations run --rm --user "$(id -u):$(id -g)" database-backup

newest="$(ls -1t backups/pametna-kupovina-*.dump | head -n 1)"
if [ -n "$upload_url" ] && [ -z "$upload_refused" ]; then
    sealed="$newest.age"
    trap 'rm -f "$sealed"' EXIT
    (umask 077 && age --encrypt --recipient "$recipient" --output "$sealed" "$newest")
    curl --fail --silent --show-error --max-time 600 \
        --upload-file "$sealed" "${upload_url%/}/$(basename "$sealed")"
    rm -f "$sealed"
    echo "Poslato van servera (šifrovano): $(basename "$sealed")"
fi

find backups -name 'pametna-kupovina-*.dump' -type f -mtime +"${keep_dumps:-14}" -delete
compose exec -T backend find /data/import-archive -type f -mtime +"${keep_archive:-30}" -delete
echo "Backup gotov: $newest"

if [ -n "$upload_refused" ]; then
    echo "$upload_refused" >&2
    exit 1
fi
