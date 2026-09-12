# Pretraga sa cenama, izbor zamene i prethodna kupovina — 10.09.2026.

Nastavak `FIXES-SEARCH-AND-RESUME-2026-09-10.md`. Izmene su lokalne; nema commita/pusha. Postojeće izmene i privatna dokumentacija nisu uklanjane.

## Implementirano

- `/api/v1/products/search` podrazumevano izostavlja proizvode bez upotrebljive cene. `includeWithoutPrice=true` uključuje i njih; tačan validan barkod ostaje vidljiv čak i bez cene. Filtriranje i prioritet cene primenjuju se pre paginacije.
- Upotrebljivost se proverava kroz `product_retailer_presence`: ponuda postoji, cena je pozitivna, datum nije budući i nije stariji od konfigurisanog `shopping.optimization.max-price-age-days` (30). Ovo je provera cenovnika na nivou lanca, ne garancija zaliha ili cene u obližnjem objektu.
- Rezultat nosi `hasUsablePrice` i `knownRetailers`. Pretraga pokazuje brend, pakovanje, trgovca, cenu i datum cenovnika; tehnički detalji su sklopljeni. Procenat poklapanja je uklonjen iz ovog prikaza.
- Android ima opciju „Prikaži i proizvode bez cene“, jasno upozorenje i dodatnu potvrdu izbora takvog proizvoda. Oznaka „Proizvod prepoznat“ ne obećava dostupnost.
- Nepokrivene stavke u preporukama nude „Pogledaj alternative“. Korisnik menja upit, bira proizvod sa cenom, proverava vrstu/masnoću/pakovanje i eksplicitno potvrđuje broj novih pakovanja. Ne postoji tiha zamena proizvoda ili automatsko popuštanje ograničenja.
- Potvrđena zamena menja aktivni spisak i pokreće novo računanje. Sačuvani planovi i čekiranja se ne menjaju. Ako računanje ne uspe posle lokalnog upisa, poruka jasno razlikuje sačuvanu izmenu od neizračunatog plana; stari rezultat se ne prikazuje kao novi.
- Prethodna kupovina ima zasebnu karticu iznad novih preporuka, sa brojem stavki, datumom i napretkom. Nastavak otvara njen postojeći ID. „Započni kupovinu po ovom planu“ je zasebna radnja, sa potvrdom ako već postoji aktivna kupovina.

## Provereno

- Backend `verify`: 154 testa, 0 failures/errors.
- Android build i 37 unit testova: uspešno.
- Osam selektivnih instrumentation testova: očuvanje kupovine posle ponovnog otvaranja baze, čekiranje i beleške, nastavak/nova kupovina, pretraga i paginacija, potvrda proizvoda bez cene, eksplicitna zamena i nepromenjena sačuvana kupovina. Koriste posebne/in-memory baze.
- Stvarni lokalni API: `Pilos mleko` vraća 16 proizvoda sa cenom; sa uključenim proizvodima bez cene ukupno 17. Barkod `4056489509400`, porodica 29943, vraća Pilos sveže mleko 1,5% sa `hasUsablePrice=false` i poznatim trgovcem Lidl. Nije dodata izmišljena cena.
- Prvi rezultati sa cenom uključuju Pilos sveže mleko 2,8% 1 l (119,99 RSD) i 3,2% 1 l (124,99 RSD), iz cenovnika 09.09.2026. Izbor između njih ostaje korisnički.
- APK instaliran preko postojeće aplikacije (`install -r`), bez deinstalacije ili brisanja podataka. Nakon testova otvorena je normalna aplikacija.
- Pre/posle poređenje svih redova kupovina i aktivnog spiska ima identičan SHA-256: 10 kupovina, zbir kupljenih stavki 4, 7 redova aktivnog spiska.
- Rezervna kopija Android baze: `infra/backups/android-pre-price-aware-20260910.tar` (ignorisan binarni fajl). Provera kopija je u privremenom direktorijumu `/private/tmp/pk-price-aware.IFSyyp`.
- Lokalni backend pokrenut na 127.0.0.1:8080; rasporedi uvoza i worker ostaju isključeni. Nisu menjani živi spiskovi preko API-ja radi testiranja.

## Preostalo — nije implementirano ovom turom

- Bezbedno dnevno osvežavanje: prvo staging/validacija obima i integriteta, pa atomarna promocija novog cenovnika; očuvati poslednji dobar skup ako uvoz pukne. Postojeći importer piše u delovima i ne treba ga samo automatski uključiti.
- Dodatni lanci tek nakon tog mehanizma i provere lokacija/pokrivenosti. Nisu aktivirani novi lanci ni novi automatski rasporedi.
- Fina razlika između pitkog voćnog i grčkog jogurta ostaje odložena ranijim dogovorom.

## M1 nastavak — 11.09.2026.

- Import sada radi odvojeni preflight nad celim preuzetim fajlom, proverava
  prazan/budući/stariji snapshot, minimalni broj redova i pad obima prema
  prethodnom stanju. Mali broj neispravnih redova zadržava postojeći status
  `SUCCEEDED_WITH_ERRORS`; većina neispravnih redova zaustavlja promociju.
- Upis svih validnih redova i obnova kataloga sada su u jednoj transakciji sa
  PostgreSQL advisory lock-om. Prethodni aktivni cenovnik ostaje vidljiv ako
  preflight ili promocija ne uspe. Dodata je regresija za prag pada obima.
- Ovo je priprema za dnevni rad, ali rasporedi nisu uključeni. Data-quality
  monitor i dalje prijavljuje stare izvore dok ne izvršimo stvarno osvežavanje.

## Kratka korisnička provera

1. Pretraži `Pilos mleko`: rezultati sa cenama i datumima; uključi proizvode bez cene da vidiš staro 1,5% mleko i upozorenje.
2. Za postojeće nepokriveno mleko otvori alternative, izaberi odgovarajuće mleko i broj pakovanja, pa potvrdi. Pre potvrde ništa se ne menja; posle nje se plan ponovo računa za lokaciju.
3. Uporedi „Nastavi prethodnu kupovinu“ i „Započni kupovinu po ovom planu“. Prvo zadržava stara čekiranja, drugo traži potvrdu za novi plan.
