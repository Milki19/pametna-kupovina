#!/usr/bin/env bash
# Na Mac-u: pokreće test skup izbora proizvoda (TestSkupMerenjeTest) nad
# kopijom baze pk_copy i čuva izveštaj u infra/logs/test-skup/.
#   bash infra/test-skup.sh            vrati najnoviji dump iz infra/backups/ u pk_copy, pa meri
#   bash infra/test-skup.sh --kopija   meri nad postojećom pk_copy (npr. posle probe migracije)
# Produkcija se ne dira. Flyway primenjuje migracije sa ovog checkout-a na pk_copy,
# kao server pri deploy-u. Pragovi (procenti) po želji: TEST_SKUP_MIN_PREPOZNATO, TEST_SKUP_MIN_ISPRAVNO.
set -euo pipefail
cd "$(dirname "$0")/.."
c=pametna-kupovina-postgres
user="$(docker exec $c printenv POSTGRES_USER)"
pass="$(docker exec $c printenv POSTGRES_PASSWORD)"

if [ "${1:-}" != "--kopija" ]; then
    dump="$(ls -t infra/backups/pametna-kupovina-*.dump 2>/dev/null | head -1)"
    test -s "$dump" || { echo "Nema dump-a u infra/backups/"; exit 1; }
    echo "Dump: $(basename "$dump")"
    docker exec $c dropdb -U "$user" --if-exists pk_copy
    docker exec $c createdb -U "$user" -T template0 pk_copy
    docker exec -i $c pg_restore -U "$user" -d pk_copy --no-owner < "$dump" 2>&1 | tail -3 || true
fi

out="$PWD/infra/logs/test-skup"
mkdir -p "$out"
echo "Merim (commit $(git rev-parse --short HEAD))..."
(cd pametna-kupovina-backend && \
    TEST_SKUP_DB_URL=jdbc:postgresql://127.0.0.1:5432/pk_copy \
    TEST_SKUP_DB_USERNAME="$user" TEST_SKUP_DB_PASSWORD="$pass" TEST_SKUP_IZLAZ="$out" \
    ./mvnw -q -B test -Dtest=TestSkupMerenjeTest -Dsurefire.failIfNoSpecifiedTests=false)
report="$(ls -t "$out"/izvestaj-*.md | head -1)"
echo "Izveštaj: $report"
sed -n '1,/^## Po izvoru/p' "$report"
echo "Posle: docker exec $c dropdb -U \"$user\" pk_copy (ako ti kopija više ne treba)."
