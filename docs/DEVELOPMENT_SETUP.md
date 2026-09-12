# Razvojno okruženje od čistog klona

Ovo je ponovljiv lokalni tok: Git klon → PostGIS baza → Spring Boot backend →
početni podaci → Android emulator. Komande se, osim gde je drugačije navedeno,
pokreću iz korena repozitorijuma.

## 1. Preduslovi

- Git;
- Docker Desktop sa Docker Compose dodatkom;
- JDK 21;
- Android Studio i Android SDK za API 36.1;
- Android emulator (AVD) sa API 26 ili novijim.

Provera najvažnijih alata:

```bash
git --version
docker version
docker compose version
java -version
```

## 2. Klon i lokalna konfiguracija baze

```bash
git clone <URL_REPOZITORIJUMA>
cd pametna-kupovina
cp infra/.env.example infra/.env
```

U `infra/.env` promeniti razvojnu lozinku. Fajl je ignorisan u Gitu i ne treba
ga commitovati.

## 3. PostGIS baza

Pokretanje postojeće lokalne Compose definicije:

```bash
docker compose --env-file infra/.env -f infra/compose.yaml up -d
docker ps --filter name=pametna-kupovina-postgres
```

Kontejner treba da postane `healthy`, a baza je dostupna samo lokalno na
`localhost:5432`. Podaci ostaju u Docker volume-u i posle običnog `down`.

Ako backend prijavi `Connection to localhost:5432 refused`, prvo proveriti
Docker Desktop, zatim status postojećeg kontejnera:

```bash
docker ps -a --filter name=pametna-kupovina-postgres
docker logs pametna-kupovina-postgres
```

Ne praviti drugu bazu i ne brisati volume da bi se rešila greška konekcije.

## 4. Backend

Vrednosti moraju biti iste kao u `infra/.env`:

```bash
cd pametna-kupovina-backend
DB_URL=jdbc:postgresql://localhost:5432/pametna_kupovina \
DB_USERNAME=pametna_kupovina \
DB_PASSWORD=<LOZINKA_IZ_INFRA_ENV> \
./mvnw spring-boot:run
```

Flyway pri prvom startu sam pravi i ažurira šemu `app`. Backend je spreman kada
sledeća komanda vrati status `UP`:

```bash
curl http://localhost:8080/actuator/health
```

Backend testovi se pokreću nezavisno od razvojne baze, u Testcontainers bazi:

```bash
cd pametna-kupovina-backend
./mvnw test
```

## 5. Početno punjenje podacima

Sledeći pozivi preuzimaju aktuelne podatke sa registrovanih spoljnih izvora.
Backend mora da radi, a računar mora imati pristup internetu. Veliki cenovnici
mogu potrajati više minuta.

Prvo uvesti proverene lokacije:

```bash
curl -X POST http://localhost:8080/api/v1/imports/retailers/LIDL/locations/latest
curl -X POST http://localhost:8080/api/v1/imports/retailers/DIS/locations/latest
```

Zatim cenovnike dostupnih lanaca:

```bash
curl -X POST http://localhost:8080/api/v1/imports/retailers/EUROPROM
curl -X POST http://localhost:8080/api/v1/imports/retailers/LIDL
curl -X POST http://localhost:8080/api/v1/imports/retailers/IDEA_RODA
curl -X POST http://localhost:8080/api/v1/imports/retailers/UNIVEREXPORT
curl -X POST http://localhost:8080/api/v1/imports/retailers/DIS
curl -X POST http://localhost:8080/api/v1/imports/maxi/latest
```

Na kraju obnoviti izvedeni katalog i proveriti status izvora:

```bash
curl -X POST http://localhost:8080/api/v1/imports/catalog/rebuild
curl http://localhost:8080/api/v1/imports/sources
```

U lokalnom režimu administratorski ključ podrazumevano nije obavezan. U
produkciji su import endpoint-i odvojeni od javnog API-ja i zaštićeni su.

## 6. Android emulator

Otvoriti `pametna-kupovina-android/` u Android Studiju. IDE pravi lokalni
`local.properties` sa putanjom do Android SDK-a. U isti fajl dodati:

```properties
BACKEND_BASE_URL=http://10.0.2.2:8080/
```

Adresa `10.0.2.2` je Android emulatorov prolaz do `localhost` računara. Posle
pokretanja AVD-a aplikacija može da se izgradi i instalira komandama:

```bash
cd pametna-kupovina-android
./gradlew testDebugUnitTest assembleDebug
./gradlew installDebug
```

Instrumentation provera zahteva već pokrenut emulator:

```bash
./gradlew connectedDebugAndroidTest
```

Za fizički telefon `10.0.2.2` ne važi. Telefon i računar moraju biti na istoj
mreži, a `BACKEND_BASE_URL` treba da koristi LAN adresu računara i port 8080.

## 7. Minimalna ručna provera

1. Pretraga `DONAT` vraća više proizvoda, a barkod `3838600041300` tačan
   canonical proizvod.
2. Stavka može biti sačuvana kao tačan proizvod ili fleksibilna kategorija.
3. Lepljenje spiska, izmena, brisanje i offline draft preživljavaju restart.
4. Trenutna ili ručno uneta lokacija vodi do tri scenarija preporuke.
5. Svaki scenario prikazuje pokrivenost, prodavnice, stavke, cenu korpe i
   procenjeni trošak puta.
6. Dugme za mapu otvara rutu do jedne ili više prodavnica.

## 8. Zaustavljanje i čuvanje podataka

```bash
docker compose --env-file infra/.env -f infra/compose.yaml down
```

Ova komanda zadržava razvojnu bazu. Opciju `down -v` ne koristiti osim kada je
namerno odobreno potpuno brisanje baze. Jednokratni backup i produkcioni restore
postupak opisani su u `infra/README.md`.
