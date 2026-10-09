# Pametna kupovina

„Pametna kupovina“ je Android aplikacija i Spring Boot servis koji od korisničkog
spiska prave predlog kupovine u jednoj ili dve obližnje prodavnice. Sistem
objedinjuje kataloge i cenovnike više trgovinskih lanaca, uparuje proizvode i u
obračun uključuje cenu korpe, put, vreme i broj stajanja.

Verzija 2.0 radi na serveru https://pametna-kupovina.duckdns.org: Android
aplikacija i web verzija na `/app` (može se dodati na početni ekran telefona).
Cene se uvoze svakog dana iz cenovnika koje lanci objavljuju po Pravilniku.

## Struktura repozitorijuma

- `pametna-kupovina-backend/` — Java 21, Spring Boot, PostgreSQL/PostGIS i
  Flyway migracije;
- `pametna-kupovina-android/` — Kotlin, Jetpack Compose, Room, Retrofit,
  WorkManager i Hilt;
- `pametna-kupovina-backend/src/main/resources/static/app/` — web verzija
  (posle izmene podići `?v=` u `index.html`);
- `pametna-kupovina-backend/docs/` — API preporuka i pravila o lokaciji;
- `infra/` — lokalna baza, server (Docker Compose, Caddy) i skripte za
  puštanje i backup;
- `play-store.md` — tekstovi za Play Console.

## Brzi početak

Baza za razvoj se pokreće iz `infra/compose.yaml`, a backend sa dnevnim
uvozom cena preko `infra/run-local-daily.sh` (detalji u
[infra/README.md](infra/README.md)). Android čita adresu servera iz
`local.properties` (primer je u `pametna-kupovina-android/local.properties.example`).

Provera koda bez pokretanja aplikacije:

```bash
cd pametna-kupovina-backend
./mvnw test

cd ../pametna-kupovina-android
./gradlew testDebugUnitTest assembleDebug
```

Backend testovi koriste Testcontainers, pa Docker mora biti pokrenut.

## Server

Produkciona Docker postavka, puštanje nove verzije i backup opisani su u
[infra/README.md](infra/README.md), a postavljanje servera na Oracle Cloud u
[infra/ORACLE.md](infra/ORACLE.md).

Konfiguracioni fajlovi sa stvarnim lozinkama i lokalni dump baze ne ulaze u Git.
