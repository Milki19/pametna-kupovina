# Infra

Ovde je sve što je potrebno da backend i baza rade na Mac-u i na serveru.

| Fajl | Namena |
| --- | --- |
| `compose.yaml` | Baza za razvoj na Mac-u (port samo na `127.0.0.1`). |
| `run-local-daily.sh` | Backend na Mac-u sa dnevnim uvozom cena. |
| `compose.production.yaml` | Server: baza, backend, Caddy (HTTPS) i backup. |
| `backend.Dockerfile`, `.dockerignore` | Slika servera od testiranog `release/backend.jar`. |
| `Caddyfile` | HTTPS i prosleđivanje na backend. |
| `.env.production.example` | Podešavanja servera; kopija `.env.production` ne ide u Git. |
| `ops/` | Skripte za puštanje, backup, proveru uvoza i vraćanje baze. |
| `ORACLE.md` | Postavljanje servera na Oracle Cloud Always Free, korak po korak. |

## Server

`compose.production.yaml` pokreće četiri servisa:

- `postgres`: PostGIS 16; port nije otvoren prema internetu;
- `backend`: Spring profil `production`, isti dnevni ciklus cena kao na Mac-u
  (svi lanci jedan za drugim posle 08:00 po beogradskom vremenu, do tri
  pokušaja dnevno); migracije baze se primenjuju pri pokretanju;
  administratorske putanje (`/api/v1/imports/**`, geokodiranje) traže
  `ADMIN_API_KEY`, koji se upisuje i na stranici `/admin`;
- `caddy`: jedini javni portovi (80 i 443), sam obnavlja HTTPS sertifikat;
- `database-backup`: proveren `pg_dump` u `backups/`; pokreće ga
  `ops/backup.sh`.

Skripte:

| Skripta | Gde | Šta radi |
| --- | --- | --- |
| `ops/release-backend.sh` | Mac | Svi backend testovi, pa jar u `release/backend.jar`. |
| `ops/deploy.sh ubuntu@IP` | Mac | Šalje postavku i jar; ako na serveru postoji `.env.production`, pokreće novu verziju. |
| `ops/start.sh` | server | Gradi sliku i pokreće sve servise, čeka da budu zdravi. |
| `ops/backup.sh` | server (cron) | Dump baze, slanje van servera (`BACKUP_UPLOAD_URL`), brisanje starih dump-ova i cenovnika. |
| `ops/check-import.sh` | server (cron) | Da li server odgovara i da li je današnji uvoz uspeo; poruka na `ALERT_URL`. |
| `ops/restore.sh backups/<dump>` | server | Briše bazu i vraća je iz dump-a (traži da se upiše DA). |
| `ops/register-chain.sh "naziv"` | Mac | Probno čita cenovnik lanca sa portala i uključuje ga u dnevni uvoz (traži admin ključ). |

Memorija je podešena za Oracle ARM mašinu sa 12 GB. Za mašinu sa 4 GB
vrednosti su u komentaru u `.env.production.example`.

## Cene i prostor na disku

`current_price_offer` sadrži samo aktuelnu ponudu po proizvodu i opsegu
(`STORE`, `STORE_FORMAT` ili `RETAILER`) i koristi se za brze preporuke.
`price_observation` je istorija promena: ne dobija novi red kada je cena ista,
ali `last_seen_date` aktuelne ponude napreduje i potvrđuje svežinu.

Originalni cenovnici se na serveru čuvaju u Docker volume-u
`price_import_archive` pod datumom i SHA-256 checksum-om; `ops/backup.sh`
briše one starije od `IMPORT_ARCHIVE_KEEP_DAYS`.

`SHOPPING_MAX_PRICE_AGE_DAYS` određuje period u kom se cena smatra svežom
(podrazumevano 30 dana). Kada za stavku postoji makar jedna sveža ponuda,
starije ponude se ne koriste za rangiranje; stara cena ostaje rezervna samo
kada za tu stavku nema nijedne sveže ponude i tada aplikacija prikazuje
upozorenje o datumu podataka.

## Lokacije i cenovnici

`app.retailer_data_source` je registar izvora: tip izvora, parser, adresa,
poslednji pokušaj i ishod. Novi izvor se uključuje tek posle jednog
kontrolnog uvoza.

Prodavnice sa adresom dolaze iz zvaničnih lokatora lanaca, nikad iz naziva
cenovnika. Prodavnica ulazi u preporuke tek kad je njena veza sa cenovnikom
potvrđena; proizvoljna veza bi prikazivala cenu iz pogrešne prodavnice.
Cenovnik čija prodavnica nema poznatu adresu prikazuje se u aplikaciji pod
„Lanci bez poznate adrese". Kad lanac preimenuje cenovnik, a novi dan ima samo
jedan, postavljene prodavnice prelaze na novi naziv same.

Kontrolisana taksonomija (tipovi proizvoda, aliasi i dodele po lancu)
povezuje opšte stavke poput `hleb`, `pivo` ili `voda` sa proizvodima
različitih lanaca. Ručno pregledana dodela se sledećim uvozom ne prepisuje.
