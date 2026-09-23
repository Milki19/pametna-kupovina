# Play Store — tekstovi i odgovori za objavu

Sve ovde se kopira u Play Console. Nalog developera, plaćanje (25 USD) i
samu objavu radi vlasnik; aplikacija i server su spremni.

## Osnovno

- **Naziv (do 30 znakova):** Pametna kupovina
- **Kratak opis (do 80):** Spisak za kupovinu koji nađe najjeftiniju prodavnicu u tvojoj blizini.
- **Kategorija:** Kupovina (Shopping)
- **Kontakt:** pametnakupovina19@gmail.com
- **Politika privatnosti:** https://pametna-kupovina.duckdns.org/privatnost
- **Brisanje naloga (URL):** https://pametna-kupovina.duckdns.org/privatnost
  (odeljak „Brisanje podataka"; u aplikaciji: meni → O aplikaciji → Obriši moje podatke)
- **Ciljna grupa:** 18+ (u cenovnicima su i pivo, vino i žestoka pića)
- **Reklame:** ne sadrži

## Pun opis (do 4000)

Napiši spisak kao i uvek — „mleko, hleb, 2x pivo, ćevapi 3kg" — a Pametna
kupovina pronađe gde je sve to najjeftinije u prodavnicama oko tebe.

Cene su iz zvaničnih cenovnika koje trgovci objavljuju po Pravilniku o
objavljivanju cenovnika: Maxi, IDEA, Roda, Lidl, Univerexport, METRO, DIS,
Super Vero, dm, Gomex i lanci širom Srbije. Osvežavaju se svakog dana.

**Šta dobijaš**
- Plan kupovine: najjeftinija korpa u jednoj prodavnici, najbolji balans cene
  i puta, ili najniža cena u dve prodavnice.
- Isti proizvod u svim lancima na jednom mestu — isti brend i pakovanje se
  porede direktno.
- Skeniraj barkod sa police i dodaj proizvod na spisak.
- Skeniraj QR sa fiskalnog računa: troškovi po mesecu, prodavnicama i
  kategorijama, i šta obično kupuješ.
- „Javi mi kad pojeftini" za proizvode koje pratiš.
- Kartice lojalnosti uvek pri ruci na kasi.
- Domaćinstvo: zajednički spisak sa ukućanima, jednim QR kodom.
- Polazna tačka po GPS-u ili upisanoj adresi.

**Privatnost**
Bez imena, e-maila i lozinke. Lokacija služi samo za jedno računanje i ne
čuva se. Bez reklama i bez praćenja. Sve podatke brišeš jednim dugmetom.

Cena u cenovniku nije potvrda da proizvoda ima na stanju; merodavna je cena u
prodavnici.

## Data safety (odgovori)

Opšte:
- Prikuplja ili deli podatke: **Da** (prikuplja; ne deli).
- Šifrovano u prenosu: **Da** (HTTPS).
- Korisnik može da zatraži brisanje: **Da** (u aplikaciji i e-mailom).

Prikupljeno (ništa se ne deli sa trećim licima, ništa nije za reklame):

| Vrsta (Play) | Šta je to kod nas | Obavezno? | Svrha |
|---|---|---|---|
| Lokacija — približna i precizna | koordinate za jedno računanje plana, ne čuvaju se | opciono | Funkcionalnost aplikacije (obrada samo u trenutku) |
| Lični podaci — ID korisnika | Google-ov broj naloga, samo ako se korisnik prijavi | opciono | Upravljanje nalogom |
| Finansijski podaci — istorija kupovine | skenirani fiskalni računi (prodavnica, iznos, stavke) | opciono | Funkcionalnost aplikacije |
| Finansijski podaci — ostalo | broj kartice lojalnosti | opciono | Funkcionalnost aplikacije |
| Aktivnost u aplikaciji — drugi sadržaj korisnika | spisak za kupovinu, potvrde „isti proizvod", prijave greške u ceni | obavezno (spisak) | Funkcionalnost aplikacije |
| Informacije o aplikaciji — dnevnici padova | zapis greške, verzija, model telefona; bez ID-a uređaja | automatski | Analitika (ispravljanje grešaka) |
| ID-ovi uređaja ili drugi ID-ovi | slučajan broj koji aplikacija napravi pri instalaciji | obavezno | Funkcionalnost aplikacije, upravljanje nalogom |

## Snimci ekrana

Telefon, 1080×2400 (najmanje 2, preporuka 4–8): početni ekran sa
troškovima, spisak, plan kupovine, cene proizvoda, Kategorije.
Snimaju se sa emulatora na traženje.

## Šta radi vlasnik

1. Nalog developera na play.google.com/console (lično ili firma), 25 USD.
2. Nova aplikacija → popuniti ovo gore → otpremiti **AAB** (Play za nove
   aplikacije ne prima APK). Pravi se sa
   `./gradlew :app:bundleRelease -PBACKEND_BASE_URL=https://pametna-kupovina.duckdns.org/`,
   potpisan istim ključem kao do sada; Play ga pri prvom otpremanju uzima kao
   „upload key" i sam potpisuje ono što ide ljudima (Play App Signing).
3. Za nov lični nalog Google traži **zatvoreno testiranje sa najmanje 12
   testera 14 dana** pre javne objave (pravilo iz 2024. — proveriti u konzoli).
