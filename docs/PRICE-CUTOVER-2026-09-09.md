# Prelazak na nove cenovnike — 9. septembar 2026.

## Završeno

Lokalna aktivna baza prešla je na nove cenovnike. Svih 205.917 aktivnih ponuda ima datum 2026-09-09.

| Lanac | Aktivne ponude | Uspešni uvozi |
|---|---:|---|
| Lidl | 2.975 | 26; 2.977 obrađenih redova, dva se svode na isti ključ ponude |
| Europrom | 8.538 | 27 |
| IDEA/Roda | 98.560 | 32 |
| Maxi | 26.062 | 40–45; svih šest konfigurisanih objekata |
| Univerexport | 69.782 | 37 |
| DIS | 0 | Nema potvrđenog dostupnog novog izvora |

Brojevi predstavljaju ponude proizvoda po cenovnom opsegu, ne nužno različite proizvode. Maxi pokriva šest konfigurisanih objekata za Valjevo/Divčibare, ne sve Maxi prodavnice Srbije. Novi lanci iz discovery registra nisu automatski aktivirani.

IDEA/Roda: osvežene su 283 zvanične lokacije i mapiranje formata. Od 284 aktivna zapisa mapiranja, 33 su VERIFIED i 251 UNLINKED. Obe ranije lokalne prodavnice (IDEA Karađorđeva 62 i RODA 407) sada su cenovno podobne. Ostale veze zahtevaju dalju proveru; ne pretpostavljati da je cela mreža mapirana.

## Šta je uklonjeno, a šta sačuvano

- Iz lokalne baze uklonjeno je 231.476 aktivnih ponuda i 269.703 istorijskih cenovnih zapisa sa datumom pre 2026-09-01. Uklonjen je i istorijski fallback za te stare cene.
- Proizvodi, kanonski identiteti, korisnički spiskovi i lokalne sačuvane kupovine nisu resetovani.
- DIS cenovni izvor je deaktiviran; njegove lokacije nisu cenovno podobne dok se ne potvrdi nov izvor. Proizvodi i lokacije su sačuvani.
- Pretraga i product_retailer_presence su obnovljeni. Prisustva svih pet lanaca zasnivaju se na cenama od 2026-09-09; DIS nema aktivno cenovno prisustvo.
- Jednokratni SQL sa proverama tačnih brojeva i završenih uvoza: `infra/sql/retire-legacy-prices-20260909.sql`. Nije Flyway migracija i ne pokretati ga ponovo naslepo.

Backup kopije, obe proverene kroz pg_restore --list:

- `infra/backups/pre-price-cutover-20260909.dump` — oko 14 MB, pre uvoza.
- `infra/backups/pre-retire-legacy-20260909.dump` — oko 21 MB, neposredno pre uklanjanja starih cena, sa novim uvozima i starim cenama.

Ovo su potpuni dump-ovi baze, ne restore test u ovoj turi. Vraćanje celog dump-a bi prepisalo stanje posle backupa: za oporavak starih cena prvo ga vratiti u odvojenu bazu, pa preneti samo potrebne zapise.

## Ispravke tokom stvarnog uvoza

1. Baza je u prvoj turi uredno spolja zaustavljena (exit 0, OOM=false). Prekinuti uvozi 28, 30 i 31 označeni su FAILED tek nakon zaustavljanja našeg backenda, zatim su ponovljeni.
2. Istovremena obnova dva kataloga otkrila je deadlock nad zajedničkim canonical_product zapisima. Dodata je PostgreSQL transakciona advisory brava oko obnove kataloga i transakcija za refreshAll. Poslednji Maxi fajl je uspešno ponovljen.
3. PriceSnapshotPolicy odbija cenovnik stariji od konfigurisanog `PRICE_IMPORT_MINIMUM_SNAPSHOT_DATE`, podrazumevano 2026-09-01, pre upisa njegovih cena. Istorijski restore zahteva eksplicitno prilagođavanje ove granice.
4. Nova CSV arhiva čuva se lokalno u `infra/import-archive/`, isključena je iz Git-a. Prvi Lidl/Europrom uvozi obavljeni su pre uključivanja arhiviranja; arhiva ove ture ne sadrži njihove fajlove.

## Verifikacija

- `./mvnw verify`: 150 testova, 0 grešaka/padova/preskakanja.
- Backend health: UP. Nema RUNNING uvoza na kraju provere.
- Probni spisak 20 kreiran je pod zasebnim client tokenom, potom deaktiviran preko API-ja. Korisnički spiskovi nisu menjani; emulator nije reinstaliran.
- Šest osnovnih stavki: hleb, mleko 1 l, jogurt 1 kg, jaja 10 kom, pivo, majonez. Najniža korpa: 464,67 RSD, Maxi + Europrom. Preporučeni balans: 537,94 RSD, jedna Maxi prodavnica. Sve cene 2026-09-09.
- Uz dodatno mleko sa eksplicitnim brendom Pilos: svih 7 stavki pokriveno; balans Lidl + Maxi 599,93 RSD, najniža korpa 577,93 RSD. Pilos dolazi iz Lidla. Provereno i posle uklanjanja starih cena.
- Kandidata za obračun u Valjevu: 13. Cene nisu garancija zaliha.

Važno preostalo ponašanje: generički hleb još može izabrati mini baget 110 g; pravila očekivane veličine hleba i pitkog naspram grčkog voćnog jogurta nisu predmet ove migracije i zahtevaju poseban rad.

## Okruženje i dalje

- Baza posle uvoza/brisanja zauzima približno 606 MB; oslobođene stranice mogu se ponovo koristiti. Nije rađen blokirajući VACUUM FULL. CSV arhiva je približno 56 MB.
- Backend radi lokalno na 127.0.0.1:8080 sa najnovijim ispravkama, limitom Java heap-a 2 GB i isključenim automatskim rasporedima. Za dnevnu upotrebu još treba podesiti redovno osvežavanje; današnji uspešni uvozi nisu dokaz sedam dnevnih ciklusa.
- Sledeće: potvrditi nov DIS izvor, proširiti proverene veze prodavnica, postepeno uključiti dodatne prehrambene lance, potom automatizovati uvoz.
- Izmene ove ture nisu commitovane niti pushovane; privatna dokumentacija ostaje lokalna.
