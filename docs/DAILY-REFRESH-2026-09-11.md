# M1 — bezbedan dnevni uvoz na Mac-u

## Šta je uvedeno

- Pre upisa se ceo cenovnik preuzima i proverava: datum, broj upotrebljivih
  redova, prag izvora, pad obima i odnos neispravnih redova.
- Cene, istorija, katalog i status uspešnog importa objavljuju se u jednoj
  transakciji po lancu / Maxi objektu. Greška vraća taj import na prethodno
  stanje. Ostali uspešni lanci ne poništavaju se zbog neuspeha jednog izvora.
- Provera jedinstvenih upisanih ponuda sprečava da duplirani CSV redovi zaobiđu
  minimalni obim. Broj CSV redova zato nije isto što i broj različitih ponuda.
- Ostaje postojeće ponašanje poslednje poznate cene za proizvod koji nestane
  iz novog fajla; preporuke moraju i dalje prikazivati datum cene. Ovo nije
  potvrda zaliha niti automatsko brisanje svih ranijih ponuda.
- Delimično čitljiv fajl može završiti kao `SUCCEEDED_WITH_ERRORS` (odbija se
  ako neispravni redovi prelaze trećinu zbirno ispravnih i neispravnih redova).
  Takav import **ne računa se** kao uspešan dan za M1.

## Dnevni ciklus

Obuhvat je namerno ograničen na Lidl, Europrom, IDEA/Roda, Univerexport i šest
već potvrđenih Maxi objekata: 508, 538, 512, 513, 541, 544. Novi lanci i nove
lokacije se ovim postupkom ne uključuju.

Profil `local-daily` proverava raspored na 15 minuta, prvi put minut nakon
pokretanja servera. Uvoz počinje posle 08:00 po vremenu u Beogradu. Ako Mac
prespava termin, pokušaj sledi nakon buđenja / pokretanja servera. Najviše tri
pokušaja dnevno, sa najmanje dva sata između početaka, i bez ponovnog
automatskog uvoza posle uspešnog dnevnog ciklusa.

Ručno pokretanje je moguće administratorskim `POST /api/v1/imports/daily`.
Preklapanje dva dnevna ciklusa sprečava zaključavanje u bazi, koje se oslobađa
i pri gašenju procesa. Sledeći ciklus označava prethodni prekinuti ciklus kao
neuspešan; ne prijavljuje ga naknadno kao uspešan.

`GET /api/v1/imports/daily` prikazuje poslednjih 21 ciklus, rezultat po lancu i
broj uzastopnih uspešnih dana. Ponovljeni uvoz istog dana ne uvećava broj dana.
Uspešan dan znači: svih pet lanaca bez grešaka, svih šest Maxi objekata, datum
cenovnika danas ili juče. Stariji uspešno učitan fajl daje upozorenje, ne uspeh.
Brojač pre današnjeg ciklusa može prikazivati niz koji se završava juče.

## Pokretanje i ograničenja

Docker Desktop i postojeća baza moraju raditi. Iz korena projekta:

```sh
bash infra/run-local-daily.sh
```

Skripta pravi proverenu kopiju testiranog backend paketa u `infra/runtime`
(naziv sadrži SHA-256), tako da sledeći razvojni build ne menja fajl koji server
trenutno koristi. Stare runtime verzije se ne brišu automatski.
Skripta pokreće tu kopiju i čita podatke za vezu
iz postojećeg lokalnog kontejnera, ne ispisuje lozinku i odbija da zameni server
koji već sluša na portu 8080. API ostaje vezan samo za `127.0.0.1`.

Ovo nije servis za automatsko pokretanje pri prijavi na Mac niti cloud worker:
posle potpunog gašenja računara/servera potrebno ga je ponovo pokrenuti.
Dok je Mac ugašen ili baza/server ne rade, nema uvoza. Terminal koji pokrene
server treba ostaviti otvoren.

Prethodni nezavisni rasporedi za generički i Maxi import ostaju isključeni,
da ne dupliraju ovaj objedinjeni ciklus. Automatski uvoz lokacija nije uključen.

Rezultati ostaju u bazi (`price_refresh_cycle`, `price_refresh_result`).
Log je `infra/logs/backend-daily.log`, uz rotaciju (10 MB po fajlu, 14 dana,
ukupno do 200 MB). Uključen je i postojeći satni data-quality izveštaj. Ovo
nije email/push obaveštenje; preostala upozorenja za lokacije treba rešavati
odvojeno od svežine cenovnika.

## Provere

- Namerno izazvana greška posle više od 500 upisa: prethodne cene, istorija,
  proizvodi, porodice i brendovi ostaju identični.
- Namerno izazvana greška tokom obnove kataloga: isto potpuno vraćanje.
- Dnevna evidencija: pet rezultata, nastavak posle neuspeha jednog lanca,
  oporavak prekinutog ciklusa, zabrana paralelnog ciklusa, ponavljanje istog dana.
- Završna backend provera: 161 test, bez neuspeha i preskakanja.
- Backup pre migracije/uvezivanja: `infra/backups/pre-daily-refresh-20260911.dump`
  (oko 15 MB). Proverena čitljivost sadržaja arhive; potpuni restore ove nove
  kopije u praznu bazu još nije rađen.

Sedam dana pouzdanog rada nije moguće dokazati jednim razvojnim korakom.
M1 ostaje otvoren do stvarnog sedmodnevnog niza i završne provere lokacija.
