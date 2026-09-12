# Pametna kupovina

„Pametna kupovina“ je Android aplikacija i Spring Boot servis koji od korisničkog
spiska prave predlog kupovine u jednoj ili dve obližnje prodavnice. Sistem
objedinjuje kataloge i cenovnike više trgovinskih lanaca, uparuje proizvode i u
obračun uključuje cenu korpe, put, vreme i broj stajanja.

Projekat je trenutno tehnička alfa. Glavni tok radi na emulatoru, ali kvalitet i
svežina podataka, operativni nadzor i završni korisnički tok još nisu na nivou
javne produkcije.

## Struktura repozitorijuma

- `pametna-kupovina-backend/` — Java 21, Spring Boot, PostgreSQL/PostGIS i
  Flyway migracije;
- `pametna-kupovina-android/` — Kotlin, Jetpack Compose, Room, Retrofit,
  WorkManager i Hilt;
- `infra/` — lokalni PostGIS i nacrt produkcionog Docker Compose okruženja;
- `docs/` — roadmap, prezentacija i razvojna dokumentacija.

## Brzi početak

Kompletan postupak od čistog klona do pokrenutog backenda i Android emulatora
nalazi se u [docs/DEVELOPMENT_SETUP.md](docs/DEVELOPMENT_SETUP.md).

Kratka provera koda bez pokretanja aplikacije:

```bash
cd pametna-kupovina-backend
./mvnw test

cd ../pametna-kupovina-android
./gradlew testDebugUnitTest assembleDebug
```

Backend testovi koriste Testcontainers, pa Docker mora biti pokrenut.

## Dalji razvoj

Jedinstven pregled završenih funkcionalnosti, poznatih rizika i puta do bete i
produkcije nalazi se u [docs/ROADMAP.md](docs/ROADMAP.md). Produkciona Docker
postavka i backup procedura opisane su u [infra/README.md](infra/README.md).

Konfiguracioni fajlovi sa stvarnim lozinkama i lokalni dump baze ne ulaze u Git.
