# Pametna kupovina na Oracle Cloud Always Free

Uputstvo za prvo postavljanje servera. Na serveru rade baza (PostGIS),
backend sa dnevnim uvozom cena i Caddy za HTTPS, svako u svom Docker
kontejneru. Ista postavka radi i na drugom Linux serveru (npr. Hetzner),
uz manje memorije u `.env.production`.

Šta je besplatno (Oracle Always Free, stanje 17.09.2026.): ARM procesori i
memorija ukupno 2 OCPU i 12 GB, 200 GB diska, 10 TB saobraćaja mesečno i
20 GB Object Storage-a. Oracle je u junu 2026. prepolovio ove limite bez
najave, pa ih pre otvaranja naloga proveriti na
<https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm>.

## 1. Nalog i mašina (radi vlasnik)

1. Otvoriti nalog na <https://www.oracle.com/cloud/free/>. Traži se kartica
   za proveru identiteta. **Home region se bira jednom i ne menja se**:
   uzeti evropski, npr. Germany Central (Frankfurt) ili Netherlands
   Northwest (Amsterdam).
2. SSH ključ na Mac-u (ako ga još nema): `ssh-keygen -t ed25519`. Javni deo je
   `~/.ssh/id_ed25519.pub`.
3. Compute → Instances → Create instance:
   - Image: Canonical Ubuntu 24.04 (aarch64);
   - Shape: Ampere `VM.Standard.A1.Flex`, 2 OCPU, 12 GB memorije;
   - Networking: nova VCN sa javnom podmrežom, „Assign a public IPv4
     address";
   - Add SSH keys: nalepiti javni ključ sa Mac-a;
   - Boot volume: 100 GB (besplatno je ukupno 200 GB).

   Ako piše „Out of capacity", pokušati drugi availability domain ili kasnije.
4. Zapisati javnu IP adresu mašine.

   Zamke u Oracle čarobnjaku (provereno 17.09.2026.):
   - **prvo shape, pa image**: svaka promena shape-a vraća image na Oracle
     Linux;
   - broj OCPU-a i memorija se ne kucaju u tabelu, nego posle klika na
     strelicu ▸ pored `VM.Standard.A1.Flex`;
   - ako je prekidač „Assign a public IPv4 address" zaključan, podmreža nije
     javna. Najlakše: ☰ → Networking → Virtual cloud networks → **Start VCN
     Wizard → VCN with Internet Connectivity**, pa u čarobnjaku za mašinu
     izabrati tu mrežu i podmrežu sa `public` u imenu.

## 2. Mreža: portovi 80 i 443

Oracle ima dve brane i obe moraju da propuste saobraćaj:

1. Networking → Virtual Cloud Networks → (VCN) → Security Lists → Default
   Security List → Add Ingress Rules: Source CIDR `0.0.0.0/0`, protokol TCP,
   Destination Port Range `80`; isto za `443`.
2. Na samoj mašini (Ubuntu slike na Oracle-u imaju svoja iptables pravila):

   ```bash
   ssh ubuntu@IP-servera
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
   sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
   sudo netfilter-persistent save
   ```

## 3. Docker na serveru

Po zvaničnom uputstvu za Ubuntu (<https://docs.docker.com/engine/install/ubuntu/>),
zatim korisniku dozvoliti Docker bez `sudo`:

```bash
sudo usermod -aG docker ubuntu
sudo timedatectl set-timezone Europe/Belgrade   # cron ispod radi po našem vremenu
exit    # pa ponovo ssh da bi grupa važila
docker compose version
```

## 4. Adresa (domen)

Besplatno: na <https://www.duckdns.org/> se prijaviti, napraviti poddomen
(npr. `pametna-kupovina`) i upisati javnu IP adresu servera. Adresa je tada
`pametna-kupovina.duckdns.org`. Caddy sam dobija HTTPS sertifikat kad DNS
pokazuje na server i portovi 80 i 443 su otvoreni.

## 5. Prvo puštanje (sa Mac-a)

Na Mac-u (svi testovi, pa slanje postavke i jar-a na server):

```bash
cd ~/Documents/pametna-kupovina
infra/ops/release-backend.sh
infra/ops/deploy.sh ubuntu@IP-servera
```

Na serveru napraviti podešavanja:

```bash
cd pametna-kupovina
cp .env.production.example .env.production
nano .env.production
```

Upisati `APP_DOMAIN`, `POSTGRES_PASSWORD` i `ADMIN_API_KEY` (dve različite
vrednosti iz `openssl rand -hex 32`).

Prenos baze sa Mac-a, da server ne kreće od prazne baze:

```bash
# na Mac-u
docker exec pametna-kupovina-postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' \
  > infra/backups/za-server.dump
scp infra/backups/za-server.dump ubuntu@IP-servera:pametna-kupovina/backups/

# na serveru
cd pametna-kupovina
ops/restore.sh backups/za-server.dump      # traži da se upiše DA, pa pokreće sve
```

Provera:

- `https://pametna-kupovina.duckdns.org/actuator/health` → `"status":"UP"`;
- `https://pametna-kupovina.duckdns.org/admin` → uneti `ADMIN_API_KEY`.

Svako sledeće puštanje nove verzije: na Mac-u `infra/ops/release-backend.sh`
pa `infra/ops/deploy.sh ubuntu@IP-servera`. Migracije baze se primenjuju same
pri pokretanju.

## 6. Dnevni poslovi na serveru (cron)

`crontab -e` na serveru, pa dodati (vreme je beogradsko, posle koraka 3):

```cron
30 4 * * * $HOME/pametna-kupovina/ops/backup.sh >> $HOME/pametna-kupovina/backups/backup.log 2>&1
0 13,19 * * * $HOME/pametna-kupovina/ops/check-import.sh >> $HOME/pametna-kupovina/backups/check.log 2>&1
```

- `backup.sh`: proveren dump baze, čuva se 14 dana; originalni cenovnici
  30 dana.
- `check-import.sh`: da li server odgovara i da li je današnji uvoz uspeo
  (uvoz kreće posle 08:00 i ponavlja se do tri puta).

Backup van servera (preporučeno): Storage → Buckets → Create Bucket (npr.
`pametna-kupovina-backup`) → Pre-Authenticated Requests → Create: target
Bucket, access „Permit object writes", datum isteka (npr. godinu dana).
Dobijeni URL (završava se sa `/o/`) upisati u `BACKUP_UPLOAD_URL`.

Obaveštenje na telefon (preporučeno): instalirati aplikaciju ntfy, pretplatiti
se na temu sa dugim tajnim imenom i upisati `ALERT_URL=https://ntfy.sh/<ime>`.

Jednom posle prvog backup-a probati vraćanje: `ops/restore.sh backups/<dump>`
(briše bazu i vraća je iz dump-a).

## 7. Aplikacija na telefonu

Release build sa adresom servera (build odbija `http://` adresu, jer release
verzija ne dozvoljava nešifrovan saobraćaj):

```bash
cd pametna-kupovina-android
./gradlew assembleRelease -PBACKEND_BASE_URL=https://pametna-kupovina.duckdns.org/
```

Za potpisan build vlasnik jednom pravi ključ i čuva ga van Git-a (bez njega se
aplikacija ne može ažurirati preko postojeće instalacije):

```bash
keytool -genkeypair -v -keystore ~/pametna-kupovina-release.jks \
  -alias pametna-kupovina -keyalg RSA -keysize 4096 -validity 10000
```

pa `pametna-kupovina-android/keystore.properties` (ne ide u Git):

```properties
storeFile=/Users/milki/pametna-kupovina-release.jks
storePassword=...
keyAlias=pametna-kupovina
keyPassword=...
```

Bez `keystore.properties` build pravi nepotpisan APK, koji se ne može
instalirati. Potpisan APK je u `app/build/outputs/apk/release/`.

Release verzija je potpisana drugim ključem od verzije iz Android Studija, pa
se debug verzija prvo ukloni sa telefona (`adb uninstall rs.pametnakupovina.app`
briše i lokalne spiskove), a zatim `adb install app/build/outputs/apk/release/app-release.apk`.

Ako je Google Maps ključ u Google Cloud konzoli ograničen na Android
aplikacije, dodati mu SHA-1 otisak release ključa:
`keytool -list -v -keystore ~/pametna-kupovina-release.jks -alias pametna-kupovina`.

## Poznati rizici

- Oracle može da ugasi besplatnu mašinu kojoj su 7 dana procesor, mreža i
  memorija ispod 20%. Backend zato odmah zauzme 3 GB (`BACKEND_JAVA_OPTIONS`).
- Oracle je menjao besplatne limite bez najave; backup van servera i ova
  postavka omogućavaju prelazak na drugi server za sat vremena.
- Administratorske putanje traže `ADMIN_API_KEY`; ključ ne deliti.
