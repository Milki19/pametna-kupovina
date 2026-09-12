# Pretraga brenda, pivo i nastavak kupovine — 10.09.2026.

## Implementirano

- Pretraga porodica proizvoda uključuje zasebno normalizovan brend i njegove aliase, uključujući `Pilos`, `mleko Pilos` i `Pilos mleko`.
- Odgovarajući brendovi računaju se jednom po upitu (materialized CTE), ne ponovo za svaku porodicu. Lokalno merenje: Pilos oko 0,38 s, mleko oko 0,23 s. To nije formalni load test.
- Sortiranje koristi stabilan ID porodice: istoimene porodice bez kanonskog predstavnika više ne ruše pretragu.
- V52 odvaja NON_ALCOHOLIC_BEER kao tip unutar postojeće kategorije BEER i dodaje posebnu nameru kupovine. Osnovno `pivo` ne prihvata taj tip. Ručno pregledane klasifikacije ostaju sačuvane.
- Preporuke nude nastavak poslednje aktivne kupovine istog spiska. Objašnjeno je da se nastavlja sačuvani, a ne upravo izračunati plan.
- Nova kupovina je odvojena opcija sa potvrdom. Stara kupovina i napredak se ne brišu. Ponovljeno pokretanje je zaštićeno i u transakciji repozitorijuma.

## Provera

- Backend: 153 testa, bez grešaka i preskakanja.
- Android: 37 unit testova i 4 odabrana instrumentation testa, svi uspešni.
- Instrumentation testovi koriste posebne test baze; nije korišćen connected-test postupak koji uklanja aplikaciju.
- Debug APK i test APK instalirani su sa očuvanjem podataka. Pre i posle: 9 sačuvanih kupovina, zbir broja kupljenih stavki 4.
- Stvarni API: Pilos pretraga vraća 146 porodica (ne znači da sve imaju aktuelnu ponudu); biranje Pilos mleka sa aktuelnom ponudom vodi u Lidl.
- Privremeni spisak 22: običan zahtev `pivo` → Lidl svetlo pivo 5%; `bezalkoholno pivo` → Maxi Bertold bezalkoholno; Pilos mleko → Lidl. Pokrivenost 3/3.
- Privremeni spiskovi 21 i 22 deaktivirani su vlasnički zaštićenim API pozivom. Korisnički spiskovi nisu menjani.
- Lokalni backend iz razvojnog okruženja (PID 89528 u trenutku provere) automatski je preuzeo kompajlirane izmene i V52. Prethodna odvojena instanca 87710 je ugašena. Nije pokrenuta treća instanca.
- Backend health: UP; nema importova u toku. Dnevni automatski importi nisu uključeni ovim izmenama.

## Rezervne kopije i granice

- `infra/backups/pre-search-beer-resume-20260910.dump` — kopija lokalne PostgreSQL baze napravljena tokom rada; automatski razvojni restart mogao je već primeniti V52, pa nije garantovano da je kopija pre te migracije.
- `infra/backups/android-pre-resume-20260910.tar` — baza Android aplikacije pre APK nadogradnje. Kopije su isključene iz Git-a.
- Stare sačuvane kupovine ostaju nepromenjene, uključujući prethodne izbore proizvoda. Za nove zamene treba ponovo izračunati preporuke.
- Nije rađen commit/push. Ranije nekomitovane izmene importera i privatna dokumentacija su sačuvane.
