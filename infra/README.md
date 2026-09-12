# Produkciono pokretanje

Ovaj direktorijum sadrži minimalan deployment za jedan Linux server:

- PostGIS baza sa trajnim Docker volume-om;
- Spring Boot API koji automatski izvršava Flyway migracije;
- odvojeni import worker, tako da veliki cenovnik ne blokira aplikaciju;
- Caddy reverse proxy sa automatskim HTTPS sertifikatom;
- dnevni store-level Maxi import za šest objekata u Valjevu i na Divčibarama;
- pripremljene zvanične izvore za Lidl, IDEA/Roda, Univerexport i DIS;
- nedeljnu proverenu sinhronizaciju svih DIS i Lidl lokacija;
- administratorski endpointi zaštićeni API ključem.

Preporučen početni server je u EU regionu, sa najmanje 4 vCPU, 8 GB RAM-a
(16 GB je sigurnije za paralelni rast podataka) i 160–200 GB NVMe prostora.
PostgreSQL port se ne objavljuje javno; spolja su potrebni samo 80/443 i
ograničeni SSH pristup.

## Preduslovi

Potreban je server sa Docker Compose-om i domen čiji DNS pokazuje na server.
Portovi 80 i 443 moraju biti dostupni. Baza nema javno izložen port.

## Pokretanje

1. Kopirati `.env.production.example` u `.env.production`.
2. Postaviti domen i dve različite, duge nasumične lozinke.
3. Iz direktorijuma `infra` pokrenuti:

   ```bash
   docker compose --env-file .env.production \
     -f compose.production.yaml up -d --build
   ```

4. Proveriti `https://<domen>/actuator/health`.
5. Proveriti worker i izvore administratorskim ključem:

   ```bash
   curl -H "X-Admin-Key: <ključ>" \
     https://<domen>/api/v1/imports/sources/worker

   curl -H "X-Admin-Key: <ključ>" \
     https://<domen>/api/v1/imports/sources
   ```

   Worker je zdrav kada odgovor sadrži `"healthy": true`. Registar izvora
   prikazuje poslednji pokušaj, uspeh, datum cenovnika i poslednju grešku.

6. Android APK izgraditi sa istim HTTPS URL-om:
   ```bash
   BACKEND_BASE_URL=https://<domen>/ \
     ../pametna-kupovina-android/gradlew \
     -p ../pametna-kupovina-android assembleDebug
   ```

Produkcioni API namerno ne izvršava sinhrone import pozive. Automatski import
radi samo u `import-worker` servisu: državni CSV izvori u 03:00, a Maxi u
04:00 po vremenu Europe/Belgrade. Kompletni zvanični DIS i Lidl lokatori
osvežavaju se nedeljom u 02:00. Lokalno su ručni POST endpointi i dalje
dostupni radi razvoja.

Za državne cenovnike worker ne ponavlja upisan datirani fajl: preko javnog
`data.gov.rs/api/1` dataset API-ja prvo otkriva najnoviji CSV resurs i tek onda
ga preuzima. XLSX prilozi se ne tretiraju kao cenovnik. Ako discovery poziv
privremeno nije dostupan, importer koristi poslednji uspešno otkriven CSV URL.

`SHOPPING_MAX_PRICE_AGE_DAYS` određuje period u kom se cena smatra svežom
(podrazumevano 30 dana). Kada za stavku postoji makar jedna sveža ponuda,
starije ponude se ne koriste za rangiranje; stara cena ostaje rezervna samo
kada za tu stavku nema nijedne sveže ponude i tada aplikacija prikazuje
upozorenje o datumu podataka.

## Model cena i prostor na disku

`current_price_offer` sadrži samo aktuelnu ponudu po proizvodu i opsegu
(`STORE`, `STORE_FORMAT` ili `RETAILER`) i koristi se za brze preporuke.
`price_observation` je istorija promena: ne dobija novi red kada je cena ista,
ali `last_seen_date` aktuelne ponude napreduje i potvrđuje svežinu.

Originalni CSV fajlovi se čuvaju u Docker volume-u `price_import_archive` pod
datumom i SHA-256 checksum-om. Taj volume je operativna arhiva, ali nije zamena
za off-site kopiju; na serveru ga treba sinhronizovati u privatni S3-kompatibilni
bucket sa verzionisanjem i lifecycle pravilom.

## Backup i vraćanje

Jednokratni, provereni PostgreSQL backup pravi se komandom:

```bash
docker compose --env-file .env.production \
  -f compose.production.yaml \
  --profile operations run --rm database-backup
```

Fajl se pojavljuje u `infra/backups/`. Na hostu ovu komandu treba pokretati
dnevno iz systemd timera ili cron-a, zatim šifrovano slati van servera. Pored
dnevnog `pg_dump` backup-a preporučeni su dnevni snapshot diska i mesečni test
vraćanja u praznu bazu. Restore se ne automatizuje u produkcionom Compose-u jer
je destruktivan: prvo se podigne prazna baza, zatim se eksplicitno izvrši
`pg_restore --clean --if-exists` nad izabranim backup fajlom.

## Dodavanje novih lanaca i lokacija

`app.retailer_data_source` je registar izvora. Za svaki lanac čuva tip izvora,
parser profil, fiksni ili discovery URL, raspored i poslednji ishod. Ne treba
unositi neproverene URL-ove niti izmišljene objekte; novi izvor se uključuje tek
nakon jednog kontrolnog importa.

Lokacijski CSV importer očekuje sledeće kolone odvojene tačka-zarezom:

```text
external_code;name;address;city;store_format_code;store_format_name;latitude;longitude;active
```

Koordinate mogu biti prazne. Tada objekat ostaje u redu za geokodiranje i nije
dostupan preporuci dok kandidat ne bude automatski ili ručno potvrđen. Za svaki
objekat se čuvaju izvor, spoljni ključ, poslednje viđenje i datum verifikacije.
Ovaj mehanizam je namerno odvojen od cenovnika: novi objekti se ne izmišljaju iz
naziva formata, već se uvoze tek iz proverljivog lokacijskog izvora.

Trenutno se automatski uvoze kompletni DIS i Lidl lokatori. Lidl ima jedan
zvanični format koji se direktno poklapa sa formatom cenovnika. DIS API daje tip
svakog objekta koji se pouzdano prevodi u tri DIS formata. Univerexport lokator
objavljuje veličinu objekta (`Mini`, `Super`, `Veliki`), ali cenovnik koristi
drugačije zone (`C0-MC1`, `C2-MC3` i druge). IDEA/Roda takođe ne objavljuje
vezu fizičkog objekta sa cenovničkim zonama I/R. Te lokacije se zato neće
masovno uključiti dok ne postoji proverljiv ključ za mapiranje; proizvoljna
veza bi korisniku prikazivala cenu iz pogrešne prodavnice.

Kontrolisana taksonomija (`product_category`, aliasi, source mappings i
`retailer_product_category`) povezuje fleksibilne stavke poput `hleb`, `pivo`
ili `voda` sa proizvodima različitih trgovaca. Dodela ima confidence i izvor;
ručno pregledana dodela se sledećim importom ne prepisuje.
