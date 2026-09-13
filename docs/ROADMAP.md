# Pametna kupovina — proizvodni roadmap

Poslednje ažuriranje: 2026-09-11

Ovaj dokument je jedinstven spisak postojećih mogućnosti, poznatih rizika,
planiranih funkcionalnosti i kriterijuma za alfa, beta i produkcionu verziju.

## Kratak odgovor: gde je projekat sada

„Pametna kupovina“ je funkcionalna tehnička alfa. Glavni tok radi od početka do
kraja: korisnik pravi spisak, bira konkretne ili fleksibilne proizvode, aplikacija
uparuje stavke, uzima lokaciju samo za aktivni obračun, računa tri scenarija i
prikazuje plan kupovine po prodavnicama.

Subjektivna procena završenosti po cilju:

| Cilj | Trenutna završenost | Najveći preostali jaz |
| --- | ---: | --- |
| Lična alfa na emulatoru | 85–90% | Dnevna pouzdanost podataka i završni UX |
| Hostovana interna beta | 60–70% | Lokacije, monitoring, kupovni režim i release proces |
| Javna beta | 40–50% | Šira pokrivenost, povratne informacije, podrška i stabilnost |
| Produkcija v1 | 25–35% | Operacije, bezbednost, pravna dokumentacija i dokazano ponašanje pod opterećenjem |

Procenti predstavljaju proizvodnu spremnost, ne količinu napisanog koda.

## Trenutno provereno tehničko stanje

- Backend: Spring Boot, Java 21, PostgreSQL/PostGIS i Flyway migracije.
- Android: Kotlin, Jetpack Compose, Room, DataStore, WorkManager, Retrofit i
  Hilt.
- Backend testovi: 114 testova prolazi.
- Android: `testDebugUnitTest` i `assembleDebug` prolaze 2026-09-01.
- Android instrumentation: svih 7 testova prolazi na Pixel 8 emulatoru
  2026-09-02; ovu proveru ponoviti za svaki beta release kandidat.
- Produkcioni Docker Compose, odvojeni import worker, Caddy HTTPS i backup
  procedura postoje kao implementaciona osnova, ali nisu potvrđeni dugotrajnim
  radom na stvarnom serveru.
- Trenutni Git worktree sadrži veliki broj necommitovanih izmena. Pre sledećeg
  većeg razvoja potreban je testiran lokalni checkpoint u logičnim commitovima;
  push nije neophodan dok vlasnik projekta ne odluči.

## Šta već postoji

### Spisak i Android aplikacija

- Jedan aktivni spisak sa dodavanjem, izmenom i brisanjem stavki.
- Lepljenje celog tekstualnog spiska.
- Količina i izbor između tačnog proizvoda i fleksibilne kategorije.
- Pretraga po nazivu i barkodu tokom kucanja.
- Izbor canonical proizvoda i ručna potvrda nejasnog uparivanja.
- Prikaz detalja proizvoda, trenutnih ponuda i istorije promena cene.
- Lokalni Room draft koji preživljava restart i prekid mreže.
- WorkManager sinhronizacija lokalnih izmena kada se mreža vrati.
- Anonimni `X-Client-Token`; nalog nije potreban.
- Dobijanje trenutne lokacije uz ručni unos kao rezervu.
- Precizna lokacija postoji samo u memoriji aktivnog obračuna.
- Prikaz tri scenarija: jedna prodavnica, preporučeni balans i najniža cena.
- Prikaz pokrivenosti stavki, izvora i datuma cene, udaljenosti, trajanja,
  troška puta, ukupnog troška i upozorenja o zalihama/ceni na kasi.
- Google Maps ruta za jednu ili više prodavnica sa browser fallback-om.

### Backend, katalog i preporuke

- Bezbedan CRUD spiskova vezan za client token.
- Canonical proizvodi, barkod identitet, normalizacija naziva i fuzzy matching.
- Product family sloj za varijante istog proizvoda i slučajeve sa više barkodova.
- Kontrolisana taksonomija, aliasi kategorija i retailer-specific robne marke
  (`Premia`, `K Plus`, `Pilos`).
- Evidencija dostupnosti proizvoda po lancu, formatu i fizičkoj prodavnici.
- Čuvanje aktuelne ponude odvojeno od istorije promena cena.
- Prioritet cene: fizička prodavnica, format prodavnice, pa ceo lanac.
- Snižena cena važi samo u deklarisanom periodu akcije.
- Zaštita od duplih price observation zapisa i ignorisanje nevažećih nultih
  cena.
- Optimizacija jedne ili dve prodavnice u zadatom radijusu.
- Trošak korpe, kilometraže, vremena i dodatnog stajanja je konfigurabilan.
- Straight-line ruta kao pouzdana rezerva i pripremljen OSRM adapter.
- Odvojen administrativni API za import, izvore, worker status i obnovu
  kataloga.

### Podaci i import

- Tolerantan CSV importer koji obrađuje velike fajlove bez držanja kompletnog
  sadržaja u memoriji.
- UTF-8, UTF-8 BOM i UTF-16LE izvori.
- Prepoznavanje važećeg cenovnika i izbacivanje mesečnog preseka da se ne prave
  duplikati.
- Automatsko otkrivanje najnovijeg javnog resursa i fallback na poslednji
  uspešan URL.
- Profil `PRAVILNIK_76_2026_CSV` za aktuelne Lidl, Europrom, Univerexport i
  IDEA/Roda fajlove.
- Store-level Maxi import za šest potvrđenih objekata u Valjevu i na
  Divčibarama.
- Automatska sinhronizacija zvaničnih Lidl i DIS lokatora.
- Izvor, poslednje viđenje, verifikacija i bezbedno deaktiviranje nestalih
  lokacija.

### Infrastruktura i privatnost

- Docker slika za backend i produkcioni Compose nacrt.
- Odvojeni API i import-worker proces.
- Caddy reverse proxy i automatski TLS sertifikat.
- PostgreSQL bez javno izloženog porta.
- API ključ za administratorske endpoint-e u produkciji.
- Health endpoint i worker heartbeat.
- PostGIS volume, arhiva originalnih cenovnika i dokumentovan `pg_dump` backup.
- Pravilo da se precizna korisnička lokacija ne čuva i ne loguje.

## Trenutno stanje lokacija

Stanje lokalne baze 2026-09-01:

| Lanac | Aktivne lokacije | Sa koordinatama | Status |
| --- | ---: | ---: | --- |
| Lidl | 86 | 86 | Kompletan zvanični API i automatska sinhronizacija |
| DIS | 53 | 53 | Kompletan zvanični API i automatska sinhronizacija |
| Europrom | 5 | 5 | Pilot; zvanični kompletan spisak je dostupan |
| Maxi | 6 | 6 | Pilot; zvanična tabela objekata je dostupna |
| IDEA / Roda | 2 | 2 | Fizički format nije potvrđeno vezan za I/R cenovničku zonu |
| Univerexport | 0 | 0 | Lokator postoji, ali nije vezan za C/MC cenovničke zone |

Zvanični izvori za naredne lokacijske importere:

- Maxi: <https://www.maxi.rs/cube-spisak-objekata>
- Europrom: <https://europrom.rs/prodajni-objekti/>
- IDEA: <https://www.idea.rs/Prodavnice>
- Univerexport: <https://www.univerexport.rs/sr/prodavnice>

## Poznati rizici koje treba rešiti pre bete

1. Podaci moraju svakog dana uspešno stići bez ručnog nadzora. Jedan uspešan
   import nije dokaz operativne pouzdanosti.
2. Fizička lokacija i važeća cenovnička zona nisu ista stvar. IDEA/Roda i
   Univerexport ne smeju dobiti proizvoljan format samo da bi se pojavili u
   preporuci.
3. Product family i deduplikacija postoje, ali su potrebni statistika kvaliteta,
   review queue i regresioni skup stvarnih problematičnih proizvoda.
4. „Najniža cena“ ponekad može imati veći ukupni trošak puta; UI mora još jasnije
   razdvojiti cenu korpe od ukupnog procenjenog troška.
5. Google Maps URL trenutno sadrži `dir_action=navigate`, što može odmah pokrenuti
   navigaciju. Korisnik želi prvo pregled rute.
6. Ne postoji kupovni režim u kom korisnik čekira proizvode tokom kupovine.
7. Osnovni CI za backend testove i Android debug build postoji, ali nema
   automatskog release build-a, potpisivanja, crash izveštavanja, metrika,
   alarma ni proverenog restore testa.
8. Debug build je ispravan, ali release konfiguracija još nema produkciono
   potpisivanje, optimizaciju i definisan proces izdavanja.
9. Hosting dokumentacija postoji, ali stvarni produkcioni server i domen još
   nisu potvrđeni dugotrajnim radom.

## Prioritetni roadmap

### M0 — stabilan razvojni checkpoint

Prioritet: P0, procena: 2–3 radna dana.

Status 2026-09-11: ponovljeni backend/Android buildovi, emulator provere i
rezervne kopije postoje. Worktree i dalje sadrži objedinjene necommitovane
izmene; ništa nije push-ovano.

- Ponovo pokrenuti kompletne backend, Android unit i instrumentation testove.
- Pregledati veliki postojeći diff i razdvojiti ga u logične lokalne commitove.
- Sačuvati snapshot/backup razvojne baze pre masovnog novog importa.
- Dokumentovati jedan ponovljiv „fresh clone → baza → backend → emulator“ tok.
- Uvesti osnovni CI koji kompajlira backend i Android i pokreće unit testove.

Kriterijum izlaza: čist ili objašnjeno kontrolisan worktree i ponovljiv build.

### M1 — pouzdani podaci i lokacije

Prioritet: P0, procena: 8–12 radnih dana.

Status 2026-09-11: novi cenovnici su lokalno uvedeni za pet lanaca, pretraga
razlikuje proizvode sa i bez cene, a importer sada radi preflight validaciju i
atomarnu promociju snapshot-a. Dnevna evidencija i objedinjeni ciklus postoje;
prvi pokušaj je osvežio Lidl, Europrom, IDEA/Roda i Univerexport, a Maxi je
uspešan. IDEA/Roda je zatim odbijena zbog promene broja cenovnih formata, pa je
zaštita pravilno označila ciklus kao FAILED i sačuvala prethodne cene. Posle
korekcije product-level provere treba ponoviti ciklus, pa tek onda računati
sedam stvarnih uzastopnih dana. Data-quality izveštaj i dalje odvojeno prijavljuje
stare izvore lokacija.

- Izvršiti kontrolisani pravi import novih cenovnika u kopiji postojeće baze.
- Izmeriti broj redova, proizvoda, porodica, aktivnih cena, preskočenih redova i
  trajanje svakog importa.
- Dodati data-quality izveštaj: duplikati, konfliktni barkodovi, neupareni
  proizvodi, nepoznate kategorije, nulte/stare cene i neočekivani pad obima.
- Uvesti kompletan zvanični Europrom spisak sa koordinatama.
- Uvesti kompletan Maxi spisak; samo potvrđen broj objekta povezati sa
  store-level cenovnikom.
- Dodati `pricing_eligible` i razlog isključenja fizičke lokacije iz obračuna.
- Uvesti IDEA/Roda i Univerexport lokacije kao `LOCATION_ONLY` dok cenovnička
  zona nije potvrđena.
- Dodati administrativni pregled izvora i lokacija koje zahtevaju ručnu proveru.
- Uvesti alarm ako dnevni import nije uspeo, kasni ili je neočekivano mali.

Kriterijum izlaza: najmanje sedam uzastopnih uspešnih dnevnih ciklusa i nijedna
neproverena lokacija u cenovnoj preporuci.

### M2 — kompletan tok stvarne kupovine

Prioritet: P0, procena: 6–9 radnih dana.

Status 2026-09-11: funkcionalni tok je implementiran i testiran na emulatoru
(offline čekiranje, nastavak/nova kupovina, beleška, zamena proizvoda i ruta).
Preostaje testiranje na fizičkom telefonu i praktična kupovina, plus sitne
UX/domenske dorade (npr. veličina hleba i vrste jogurta).

- Dodati kupovni režim posle izbora scenarija.
- Prikazati stavke grupisane po prodavnicama i redosledu obilaska.
- Omogućiti čekiranje „kupljeno“, poništavanje i progres po prodavnici i celoj
  kupovini.
- Čuvati stanje lokalno da spisak radi i bez mreže u prodavnici.
- Omogućiti „nije pronađeno“, „preskoči“ i opcionu belešku/stvarnu cenu.
- Po završetku ponuditi arhiviranje kupovine, bez brisanja originalnog plana.
- Promeniti navigaciju tako da prvo otvara pregled Google Maps rute, a korisnik
  sam pritiska „Pokreni/Navigiraj“.
- Za više prodavnica poslati međuodredišta redosledom iz plana.
- Poboljšati trenutnu lokaciju: retry, isključene lokacijske usluge, zastarela
  poslednja lokacija i jasna objašnjenja dozvole.
- Jasnije prikazati nepotpun scenario, nedostupne stavke, cenu korpe i ukupni
  procenjeni trošak.

Kriterijum izlaza: korisnik može od praznog spiska do završene kupovine bez
ručnog popravljanja baze ili korišćenja terminala.

### M3 — hostovana interna beta

Prioritet: P0, procena: 5–8 radnih dana.

- Izabrati VPS ili Windows 11 PC host, uz jasan trade-off dostupnosti.
- Podesiti domen ili stabilan bezbedan tunnel, HTTPS i produkcione tajne.
- Podesiti dnevni backup baze i arhive van istog računara.
- Uraditi najmanje jedan restore u praznu bazu.
- Uključiti worker rasporede, health check, osnovne metrike i obaveštenje o
  neuspelom importu.
- Dodati rate limiting za javne endpoint-e i ograničenja veličine zahteva.
- Napraviti potpisan interni Android build sa produkcionim backend URL-om.
- Definisati verzionisanje baze/API-ja i Android aplikacije.
- Napisati minimalnu politiku privatnosti, uslove korišćenja i kontakt za
  prijavu pogrešne cene.

Kriterijum izlaza: aplikaciju može instalirati 5–20 pozvanih korisnika, backend
radi bez razvojnog računara i kvar importa se primećuje istog dana.

### M4 — zatvorena beta i stabilizacija

Prioritet: P0/P1, procena: dodatnih 10–15 radnih dana.

- Pustiti 20–50 testera iz najmanje dva grada/područja sa dobrom pokrivenošću.
- Dodati anonimnu prijavu pogrešne cene, zatvorene prodavnice, pogrešnog
  proizvoda i nedostupne zalihe.
- Uvesti crash reporting bez beleženja precizne lokacije ili sadržaja spiska.
- Pratiti uspešnost toka: napravljen spisak, uspešan match, preporuka i završena
  kupovina; bez čuvanja osetljivih podataka koji nisu neophodni.
- Dodati accessibility proveru, veće fontove, screen reader oznake i kontrast.
- Testirati slabu mrežu, prekid procesa, promenu uređaja, stare cenovnike i
  delimično nedostupan backend.
- Izmeriti performanse pretrage, importa i preporuke na produkcionom obimu.
- Ispraviti najčešće probleme iz realnih kupovina pre dodavanja novih velikih
  funkcija.

Kriterijum izlaza: najmanje dve nedelje stabilne upotrebe, bez P0/P1 grešaka i
sa poznatim kvalitetom podataka za svaku podržanu oblast.

### M5 — javna beta

Prioritet: P1, procena: dodatnih 15–25 radnih dana.

- Proširiti lokacije i cenovničko mapiranje na dovoljan broj gradova.
- Uvesti više spiskova, imenovanje spiska i jednostavnu arhivu kupovina.
- Dodati favorite, osnovni budžet i upozorenje na značajnu promenu cene.
- Dodati status stranice, FAQ, podršku i proces odgovora na prijave korisnika.
- Automatizovati build/release pipeline i staged rollout.
- Uraditi bezbednosni pregled API-ja, Docker hosta, tajni, backup-a i zavisnosti.
- Finalizovati politiku privatnosti i pravila korišćenja za stvarni obim podataka.

Kriterijum izlaza: aplikacija je dostupna široj grupi bez ručnog onboardinga, a
incidenti, podaci i korisničke prijave imaju vlasnika i definisan odgovor.

### M6 — produkcija v1

Prioritet: P1, procena: dodatnih 20–35 radnih dana posle javne bete.

- Dokazati stabilan dnevni import i backup/restore kroz duži period.
- Definisati ciljanu dostupnost, retention, incident proceduru i održavanje.
- Završiti Play Store produkcioni listing, potpisivanje, staged rollout i
  rollback plan.
- Uvesti bezbedno upravljanje administratorskim pristupom i rotaciju tajni.
- Proveriti performanse i cenu infrastrukture na realnom broju korisnika.
- Ukloniti ili jasno označiti svaki lanac/grad čiji podaci nisu dovoljno
  pouzdani.
- Tek posle stabilnosti uključivati monetizaciju koja ne menja rezultat
  objektivne preporuke.

Kriterijum izlaza: proizvod može svakodnevno da radi bez prisustva developera,
a greška može da se otkrije, ograniči i vrati iz backup-a.

## Funkcionalnosti posle stabilne bete

Ove funkcije imaju vrednost, ali ne treba da blokiraju osnovnu betu.

### P1 — visoka korisnička vrednost

- Više spiskova, šabloni i ponavljanje prethodne kupovine.
- Deljenje porodičnog spiska i sinhronizacija između više uređaja.
- Barcode skener kamerom, pored postojećeg ručnog unosa barkoda.
- Favoriti i praćenje proizvoda.
- Upozorenje kada omiljeni proizvod pojeftini ili uđe u akciju.
- Budžet kupovine i upozorenje pre prelaska limita.
- Unutrašnji map preview sa markerima prodavnica, pre prelaska u Google Maps.
- Zamena nedostupnog proizvoda ekvivalentnom varijantom uz potvrdu korisnika.
- Ocena kvaliteta preporuke i jednostavno objašnjenje „zašto je ovo izabrano“.
- Pitanje korisniku kada je unos dvosmislen. Isti pojam često znači dve
  različite kupovine: „belo meso“ je svež pileći file za roštilj, a u katalogu
  je najčešće dimljeni narezak; isto važi za „vrat“ (svež za roštilj naspram
  suvog vrata u slajsu) i „kobasice“. Umesto tihog biranja, aplikacija treba
  da ponudi kratko pojašnjenje („sveže za roštilj“ ili „suvo/narezak“) i da
  zapamti izbor za sledeći put.

### P2 — fiskalni računi i istorija kupovine

- Fotografisanje ili uvoz fiskalnog računa.
- OCR i, gde je pouzdano dostupno, čitanje QR podataka fiskalizacije.
- Obavezni ekran za potvrdu pre upisa proizvoda, količine i stvarne cene.
- Povezivanje stavke računa sa canonical proizvodom uz confidence i review.
- Istorija stvarno plaćenih cena, prodavnica i kategorija.
- Mesečni pregled potrošnje, najčešće kupovine i odstupanje od budžeta.
- Predlog spiska na osnovu ponavljanja, ali bez automatske kupovine.
- Izvoz i potpuno brisanje lične istorije.

Pre ovog modula potrebni su korisnički nalog ili bezbedan migration-friendly
identitet, enkripcija osetljivih podataka, retention pravila i pravna provera.

### P2/P3 — pametne preporuke i AI

- Predviđanje kada će ponestati često kupovan proizvod.
- Predlog jeftinije ekvivalentne korpe uz ograničenja korisnika.
- Objašnjivi nedeljni uvidi u navike i potrošnju.
- Detekcija neuobičajene cene ili moguće greške u cenovniku.
- Pomoć pri OCR normalizaciji i uparivanju računa.

AI ne treba koristiti za osnovnu aritmetiku, određivanje stvarne cene ili
skriveno rangiranje sponzorisanog sadržaja. Prvo je potreban dovoljan skup
potvrđenih kupovina i jasna saglasnost korisnika.

### P2 — početni ekran, akcije i sadržaj

- Personalizovane aktuelne akcije iz podržanih cenovnika.
- Akcije prodavnica u blizini, samo posle eksplicitnog korišćenja lokacije.
- Jasno odvojene organske preporuke, plaćeni oglasi i obaveštenja sistema.
- Filtriranje po lancu, kategoriji, budžetu i periodu akcije.
- Deep link iz akcije u detalje proizvoda ili novi spisak.

## Monetizacija — redosled i zaštitna pravila

### Najrealniji modeli

1. Jasno označene sponzorisane kartice lanaca ili brendova samo na početnom
   ekranu i u posebnoj sekciji akcija.
2. Premium pretplata za naprednu istoriju, budžete, više price alert-a,
   porodično deljenje i napredne analize računa.
3. B2B agregirani uvidi za trgovce, samo ako se mogu proizvesti bez prodaje
   individualnih spiskova, računa ili precizne lokacije.
4. Partnerstva/affiliate samo kada je jasno ko plaća i kada ne menjaju rang
   „najjeftinije“ ili „preporučeni balans“.

### Pravila koja ne treba prekršiti

- Plaćanje ne sme promeniti rezultat objektivne optimizacije.
- Svaki oglas mora biti označen kao sponzorisan.
- Trgovac ne dobija korisnikov spisak, račun ili preciznu lokaciju.
- Organski i plaćeni rezultat moraju biti vizuelno i tehnički odvojeni.
- Monetizaciju ne uključivati pre stabilne javne bete i merenja poverenja.

## Potrebne promene modela baze

### Pre bete

- `store.pricing_eligible` ili ekvivalentna tabela podobnosti.
- Razlog i datum potvrde/isključenja lokacije iz obračuna.
- Izvor i stabilni spoljni identifikator za svaki fizički objekat.
- Data-quality snapshot po importu i očekivani opseg redova po izvoru.
- Review queue za konfliktne product family kandidate, kategorije i barkodove.

### Za kupovni režim

- Lokalno stanje kupljeno/preskočeno, beleška i opciona stvarna cena.
- Kasnije server-side `shopping_trip` i `shopping_trip_item` ako se uvode
  istorija, više uređaja ili porodično deljenje.

### Za račune i monetizaciju

- Odvojeni modeli za receipt, receipt line, match review i purchase history.
- Eksplicitni consent, retention i deletion status.
- Odvojeni sponsored campaign/impression/click modeli koji ne ulaze u tabelu
  organskih preporuka.

## Procena vremena

Pretpostavke:

- jedna iskusna osoba koja poznaje postojeći kod;
- puno radno vreme znači oko 30 fokusiranih razvojnih sati nedeljno;
- povremeni rad znači približno 10–12 sati nedeljno;
- procena uključuje implementaciju i testiranje, ali ne čekanje odgovora
  trgovaca ili Play Store review-a.

| Cilj | Puno radno vreme | Povremeni rad | Obim |
| --- | --- | --- | --- |
| Lična alfa | Već dostupna | Već dostupna | Emulator i lokalni backend |
| Hostovana interna beta | 4–7 nedelja | 3–5 meseci | M0–M3 |
| Stabilna zatvorena beta | 6–10 nedelja ukupno | 5–7 meseci ukupno | M0–M4 |
| Javna beta | 9–15 nedelja ukupno | 7–10 meseci ukupno | M0–M5 |
| Produkcija v1 | 13–22 nedelje ukupno | 10–15 meseci ukupno | M0–M6 |

Ako se beta svede na jedan grad i četiri dobro pokrivena lanca, interna beta se
može dobiti bliže donjoj granici. Nacionalna pokrivenost i kompletno mapiranje
svih formata pomeraju procenu ka gornjoj granici.

Skener računa, AI, porodični nalozi i monetizacija nisu uključeni u vreme do
osnovne bete. Svaki od tih većih modula može dodati približno 3–8 nedelja rada,
zavisno od tačnosti i pravnih zahteva.

## Gde dodatna pomoć najviše vredi

1. **Backend/data inženjer** — najveće ubrzanje. Može paralelno da radi
   lokacijske importere, mapiranje cenovničkih zona, data-quality metrike i
   performanse velikih importa.
2. **Android/product dizajn** — kupovni režim, mapa, accessibility i jasnija
   objašnjenja scenarija. Kratak angažman dobrog dizajnera može sprečiti mnogo
   naknadnog prepravljanja Compose ekrana.
3. **DevOps/security pomoć** — nekoliko fokusiranih dana za VPS hardening,
   backup/restore, monitoring, rate limiting i CI/CD značajno smanjuje rizik
   hostovane bete.
4. **QA i stvarni beta korisnici** — ne mogu se zameniti dodatnim kodom. Potrebni
   su različiti telefoni, lokacije, spiskovi i stvarne kupovine.
5. **Pravna/privacy konsultacija** — nije hitna za anonimnu zatvorenu betu, ali
   postaje važna pre javne distribucije, a obavezna pre računa, naloga,
   profilisanja ili oglašavanja.

AI/ML stručnjak trenutno nije prioritet. Projekat prvo mora prikupiti dovoljno
tačnih, potvrđenih podataka i dokazati da osnovni tok rešava problem korisniku.

## Predloženi neposredni redosled

1. M0: stabilizovati i lokalno checkpointovati trenutno veliko proširenje.
2. M1: pravi import, data-quality izveštaj, Europrom/Maxi lokacije i
   `pricing_eligible`.
3. M2: čekiranje kupovine i Google Maps pregled rute.
4. M3: hostovati internu betu i pustiti malu grupu ljudi.
5. Na osnovu njihovih stvarnih kupovina odlučiti da li slede računi, akcije,
   porodični spisak ili širenje podataka na nove gradove.
