# Recommendation API (`PK-046`–`PK-056`)

## Tok

1. `POST /api/v1/shopping-lists/{listId}/matching` obrađuje sve stavke.
2. Tačne stavke sa statusom `NEEDS_CONFIRMATION` potvrđuju se preko
   `PUT /api/v1/shopping-lists/{listId}/items/{itemId}/match`.
3. `GET /api/v1/shopping-lists/{listId}/recommendations` vraća tri scenarija.

Svi zahtevi koriste `X-Client-Token`. Recommendation zahtev prima `latitude`,
`longitude` i opcioni ISO datum `date`; precizna lokacija ostaje request-only.

## Pravila stavki

- `EXACT_PRODUCT` koristi potvrđen kanonski proizvod. Stavka koja čeka važnu
  potvrdu blokira optimizaciju.
- `FLEXIBLE_CATEGORY` koristi kategoriju i opciona ograničenja brenda,
  minimalnog/maksimalnog pakovanja i bazne jedinice (`g`, `ml`, `piece`).
- `UNMATCHED` znači grešku uparivanja; `NO_VALID_PRICE` znači da je zahtev
  poznat, ali nema važeću cenu u izabranom scenariju. Nijedna stavka se ne
  uklanja iz odgovora.

## Pravilo važeće cene

Za traženi datum bira se poslednja cena čiji `price_date` nije posle tog
datuma. Scope prioritet je:

1. konkretan objekat (`store_id`);
2. format objekta;
3. ceo lanac.

Snižena cena se koristi samo kada traženi datum pripada deklarisanom periodu
akcije. U suprotnom se koristi redovna cena; ponuda bez važeće vrednosti se ne
računa.

## Optimizacija

Kandidati su najviše 20 proverenih aktivnih objekata u konfigurisanom radijusu.
Algoritam proverava svaku pojedinačnu prodavnicu i svaku kombinaciju dve
prodavnice (najviše 190 parova):

- `SINGLE_STORE` — najjeftinija poznata korpa u jednoj prodavnici;
- `RECOMMENDED_BALANCE` — najmanji zbir korpe, puta, vremena i stajanja;
- `LOWEST_PRICE` — najniža cena korpe bez rangiranja po putu.

Trošak kilometra, vrednost vremena, trošak stajanja, prosečna brzina za
vazdušnu procenu, radijus i limit kandidata nalaze se u `application.properties`
i vraćaju se kroz `assumptions`.

Svaki scenario sadrži `priceSources`, `dataAsOf`, korpu, put, vreme, broj
stajanja, uštedu prema jednoj prodavnici i sve neuparene/nedostupne stavke.
Odgovor uvek navodi da zalihe i cena na kasi nisu garantovane.

## Routing

Podrazumevani provider koristi vazdušnu udaljenost i rezultat označava kao
aproksimaciju. OSRM adapter se uključuje konfiguracijom i vraća realnu
udaljenost i trajanje. Keširaju se samo javni parovi prodavnica; korisnička
lokacija se nikada ne kešira.
