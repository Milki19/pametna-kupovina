#!/usr/bin/env bash
# Sa Mac-a: probno čitanje i uključivanje lanca sa portala otvorenih podataka.
# Proba ne upisuje nijednu cenu; registracija pravi lanac i njegov izvor, pa ga
# sutrašnji dnevni ciklus uvozi. Lanac se traži po delu naziva, bez brige o
# velikim slovima:
#
#   infra/ops/register-chain.sh "dm drogerie markt" "Domaca trgovina"
#
# Ključ (isti kao ADMIN_API_KEY u .env.production na serveru) se uzima, tim
# redom: iz promenljive ADMIN_KEY, iz fajla ADMIN_KEY_FILE ili
# ~/.pametna-kupovina-admin-key, pa tek onda pitanjem na terminalu. Nijedan od
# ta tri načina ne ostavlja ključ u istoriji komandi. Adresa servera se menja
# kroz BASE_URL.
set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "Upotreba: $0 \"deo naziva lanca\" [još naziva…]" >&2
    exit 2
fi

base_url="${BASE_URL:-https://pametna-kupovina.duckdns.org}"
key_file="${ADMIN_KEY_FILE:-$HOME/.pametna-kupovina-admin-key}"

if [ -z "${ADMIN_KEY:-}" ] && [ -r "$key_file" ]; then
    ADMIN_KEY="$(head -n 1 "$key_file")"
fi

# Pitanje ide na sam terminal, ne na stdin: kad skriptu pokrene nešto drugo
# (Run dugme, cron, cev), stdin ume da bude zatvoren ili da već sadrži nešto,
# pa bi `read` pokupio to umesto ukucanog ključa i server bi vratio 401.
if [ -z "${ADMIN_KEY:-}" ] && [ -r /dev/tty ]; then
    printf 'Admin ključ: ' > /dev/tty
    read -r -s ADMIN_KEY < /dev/tty || true
    printf '\n' > /dev/tty
fi

# Nalepljen ključ često ponese razmak ili prelom reda, a server poredi doslovno.
ADMIN_KEY="$(printf '%s' "${ADMIN_KEY:-}" | tr -d '[:space:]')"

if [ -z "$ADMIN_KEY" ]; then
    echo "Nemam ključ. Upiši ga jednom u $key_file" >&2
    echo "  (umesto <kljuc> pravi ključ):" >&2
    echo "  printf %s '<kljuc>' > $key_file && chmod 600 $key_file" >&2
    exit 2
fi

export ADMIN_KEY BASE_URL="$base_url"

python3 - "$@" <<'PY'
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

kljuc = os.environ["ADMIN_KEY"]
baza = os.environ["BASE_URL"].rstrip("/")


def zovi(putanja, metod="GET"):
    zahtev = urllib.request.Request(
        baza + putanja,
        method=metod,
        headers={"X-Admin-Key": kljuc},
    )
    try:
        with urllib.request.urlopen(zahtev, timeout=300) as odgovor:
            return json.load(odgovor)
    except urllib.error.HTTPError as greska:
        telo = greska.read().decode("utf-8", "replace").strip()

        if greska.code == 401:
            raise SystemExit(
                "Server nije prihvatio ključ. Mora da bude isti kao "
                "ADMIN_API_KEY u .env.production na serveru:\n"
                "  ssh ubuntu@<server> \"grep ADMIN_API_KEY "
                "pametna-kupovina/.env.production\""
            )

        raise SystemExit(
            "Server je odbio %s %s: %s %s"
            % (metod, putanja, greska.code, telo[:300])
        )


kandidati = zovi("/api/v1/imports/catalog")

if not isinstance(kandidati, list):
    raise SystemExit("Neočekivan odgovor na spisak kandidata: %r" % kandidati)

print("Kandidata na portalu: %d" % len(kandidati))
neuspeli = 0

for trazeni in sys.argv[1:]:
    igla = trazeni.lower()
    nadjeni = [
        kandidat
        for kandidat in kandidati
        if igla in (
            (kandidat.get("organizationName") or "")
            + " "
            + (kandidat.get("title") or "")
        ).lower()
    ]

    if not nadjeni:
        print("\n== %s: nema takvog kandidata" % trazeni)
        neuspeli += 1
        continue

    if len(nadjeni) > 1:
        print("\n== %s: naziv odgovara na %d kandidata, preciziraj:"
              % (trazeni, len(nadjeni)))
        for kandidat in nadjeni:
            print("   %s — %s" % (kandidat["id"], kandidat.get("title")))
        neuspeli += 1
        continue

    kandidat = nadjeni[0]
    print("\n== %s (id %s)" % (kandidat.get("title") or trazeni, kandidat["id"]))

    proba = zovi("/api/v1/imports/catalog/%s/probe" % kandidat["id"], "POST")
    procitano = proba.get("rowsRead") or 0

    # Server šalje brojeve redova, a ne procente: udeli se računaju ovde.
    def udeo(polje):
        if not procitano:
            return "—"
        return "%d%%" % round(100.0 * (proba.get(polje) or 0) / procitano)

    print("   ocena: %s | redova: %s | upotrebljivo: %s | cena: %s | "
          "barkod: %s | najnoviji datum: %s | cenovnika u fajlu: %d"
          % (
              proba.get("verdict"),
              procitano,
              udeo("rowsUsable"),
              udeo("rowsWithPrice"),
              udeo("rowsWithBarcode"),
              proba.get("newestPriceDate"),
              len(proba.get("priceListNames") or []),
          ))

    for nalaz in proba.get("findings") or []:
        print("     - %s" % nalaz)

    if proba.get("verdict") == "REJECTED":
        print("   NIJE uključen: cenovnik nije prošao probu.")
        neuspeli += 1
        continue

    upisan = zovi(
        "/api/v1/imports/catalog/%s/register?allowReview=true" % kandidat["id"],
        "POST",
    )
    print("   uključen kao %s (%s), nov izvor cena: %s"
          % (
              upisan.get("retailerName"),
              upisan.get("retailerCode"),
              "da" if upisan.get("priceSourceCreated") else "već postojao",
          ))

if neuspeli:
    raise SystemExit("\n%d lanac/lanaca nije uključeno." % neuspeli)

print("\nGotovo. Lanci ulaze u sledeći dnevni ciklus, "
      "pod \u201ELanci bez poznate adrese\u201C.")
PY
