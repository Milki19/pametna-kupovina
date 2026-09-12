# Provera izvora — 9. septembar 2026.

## Sačuvano i provereno

- `4650fc9`: M2 offline kupovina, količine, ukusi, sinhronizacija i pregled rute.
- `d130ce8`: discovery kataloga na latinici i ćirilici, jednina i množina; deduplikacija po portalDatasetId; bez delimičnog upisa ako upit ne uspe.
- Backend: 146 testova, bez grešaka i preskakanja. Android: 37 unit testova iz Gradle keša i uspešan assembleDebug. Emulator testovi nisu ponavljani niti aplikacija reinstalirana.
- Dokumentacija nije uključena u commit. Push nije izvršen.

## Stanje lokalne baze

Postojeći kontejner pametna-kupovina-postgres bio je ugašen; pokrenut je bez promene volumena. Šema je V51, bez novih migracija. Backend je pokrenut na 127.0.0.1:8080 sa isključenim rasporedima uvoza.

| Lanac | Broj aktuelnih ponuda | Datumi cena u bazi |
|---|---:|---|
| DIS | 25.740 | 2026-02-23 |
| Europrom | 5.133 | 2026-03-02 |
| IDEA/Roda | 106.595 | 2026-03-09 |
| Lidl | 2.233 | 2026-03-02 |
| Maxi | 27.150 | 2026-08-21–2026-08-26 |
| Univerexport | 102.349 | 2026-03-16 |

Nove cene nisu uvezene. Jedina promena sadržaja baze u ovoj proveri jeste registracija 64 kandidata za CSV izvore, svi za dalju proveru. To nije broj prehrambenih lanaca, niti potvrda kvaliteta ili svežine njihovih cena.

## Nalazi iz javnog API-ja

Pretraga `q=cenovnici` vraćala je 6 skupova i propuštala ćirilične naslove. Dodatne pretrage sada pokrivaju oba pisma i oba oblika reči. Registar je prethodno bio prazan.

- DIS: registrovani stari dataset URL vraća HTTP 404; zamena još nije potvrđena.
- IDEA: portal sada pokazuje `https://ideacenovnici.blob.core.windows.net/cenovnici/drzavni/cene_proizvoda_ideamarketi.csv`. Registrovani direktni URL u bazi je stari arhivski fajl; postojeći discovery URL je validan. Portal ima i XLSX mapiranje prodavnica.
- Europrom: `https://api.nitsolutions.rs/api/v1/external/cenovnik/00fc017c-0f22-40c2-9e38-50f4c4a66f80.csv`.
- Lidl: `https://kompanija.lidl.rs/content/download/165294/fileupload/cene_proizvoda_Lidl.csv`; pomoćni EAN.csv nije cenovnik.
- Univerexport: `https://ucloud.univerexport.rs/opendata/univer.csv`.
- Delhaize: pronađen i zajednički CSV `https://tsmdelhaizeserbia.delhaize.rs/PublicDoc/cene_proizvoda_Delhaize.csv`. Potrebno proveriti formate i vezu sa prodavnicama pre korišćenja umesto lokalnih Maxi fajlova.
- Direktan Maxi GraphQL upit za 09.09.2026. u ovoj proveri vratio je HTTP 500. To nije dokaz da današnji cenovnici ne postoje.

Datumi izmene metapodataka na portalu nisu dokaz datuma cena unutar fajla. Sadržaj ovih novih fajlova još nije validiran.

## Lokacije i nastavak

Upit po polju city za Valjevo nalazi cenovno podobne: Europrom 4, Lidl 1, Maxi 5; IDEA/Roda ima 2 nepodobne lokacije. Ovo nije pun geografski inventar: neke lokacije mogu imati nepotpuno polje city.

1. Napraviti nov backup pre uvoza cena.
2. Kontrolisano preuzeti i validirati sadržaj postojećih izvora: datume, formate, kolone, obim i cenovne jedinice.
3. Prvo osvežiti Lidl/Europrom za Valjevo; proveriti rezultate iste korpe u više lanaca.
4. Osvežiti i potvrditi IDEA mapiranje objekat–cenovnik; pronaći DIS zamenu.
5. Odvojeno pregledati nove kandidate po delatnosti; ne uključivati sve automatski.
6. Tek posle provera uključiti dnevni uvoz i pratiti sedam uspešnih dnevnih ciklusa. Hosting i beta ostaju naredne faze.

Pitki voćni naspram grčkog jogurta i dalje je odloženo unapređenje.
