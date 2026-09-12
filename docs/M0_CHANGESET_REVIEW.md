# M0 pregled skupa izmena

Datum pregleda: 2026-09-02

## Rezultat pregleda

- Grana je `main`, a polazni commit je `690ca1f`.
- Postoji 50 izmenjenih praćenih fajlova i 52 nepratena putanjska unosa u
  kratkom Git statusu.
- Praćeni diff sadrži približno 4.551 dodatih i 448 uklonjenih linija.
- `git diff --check` ne prijavljuje whitespace greške.
- Lokalni `.env`, `.env.production` i dump fajlovi baze ignorisani su u Gitu.
- Jedini namerni binarni dokument je projektna prezentacija u `docs/`.

Izmene nisu pogodne za jedan nečitljiv „mega commit“, ali ni za agresivno
razdvajanje pojedinačnih backend hunkova: Flyway migracije V25–V38, importer,
katalog i veliki integracioni test razvijani su zajedno. Najbezbednija podela
koja zadržava proverljive checkpoint-e ima četiri celine.

## Predloženi lokalni checkpoint commitovi

### 1. Backend podaci, katalog i preporuke

Predlog poruke:

```text
feat(backend): expand retailer data pipeline and product catalog
```

Obuhvat:

- sav Java i SQL kod u `pametna-kupovina-backend/`;
- migracije V25–V38;
- državni i Maxi import, aktuelne cene i istorija;
- DIS/Lidl proverene lokacije;
- product family, taksonomija i dostupnost po trgovcu;
- uparivanje, preporuke i pripadajući testovi.

Iz ovog commita treba izostaviti samo backend `Dockerfile` i `.dockerignore`,
jer pripadaju infrastrukturnoj celini.

Provera posle commita:

```bash
cd pametna-kupovina-backend
./mvnw test
```

### 2. Android tok proizvoda i navigacija

Predlog poruke:

```text
feat(android): add product families, resilient drafts and store routes
```

Obuhvat:

- svi izmenjeni i novi fajlovi u `pametna-kupovina-android/`;
- Room schema 3 i migration test;
- mrežni modeli, repository i ViewModel tok;
- izbor proizvoda/porodice, prikaz preporuke i Google Maps ruta;
- unit i instrumentation testovi;
- `local.properties.example` i Android razvojna beleška.

Provera posle commita:

```bash
cd pametna-kupovina-android
./gradlew testDebugUnitTest assembleDebug
```

Uz pokrenut emulator:

```bash
./gradlew connectedDebugAndroidTest
```

### 3. Produkciona infrastruktura i backup

Predlog poruke:

```text
chore(infra): add production deployment and backup foundation
```

Obuhvat:

- `infra/` osim lokalnih backup dumpova;
- backend `Dockerfile` i `.dockerignore`;
- `.gitignore` pravila za tajne i dumpove;
- API, import worker, PostGIS, Caddy i backup servis.

Provera posle commita:

```bash
docker compose --env-file infra/.env.production.example \
  -f infra/compose.production.yaml config --quiet
```

Ova komanda potvrđuje strukturu Compose fajla, ali ne pokreće produkcione
servise niti koristi primer lozinki za stvaran deployment.

### 4. Dokumentacija i osnovni CI

Predlog poruke:

```text
chore(project): document setup and add basic CI
```

Obuhvat:

- `.github/workflows/ci.yml`;
- korenski `README.md`;
- roadmap, ovaj pregled i projektna prezentacija u `docs/`.

CI namerno pokreće backend testove i Android unit test/debug build. Android
instrumentation za sada ostaje lokalna release provera jer emulator u svakom CI
push-u znatno usporava najosnovniji signal.

## M0 izlazno stanje

M0 može da se smatra zatvorenim kada vlasnik odobri i lokalno se naprave ova
četiri commita, pa se na čistom statusu još jednom potvrde backend i Android
provere. Nijedan M0 korak ne zahteva push na udaljeni repozitorijum.
