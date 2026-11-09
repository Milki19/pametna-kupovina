# Pravilo za preciznu korisničku lokaciju

## Obim

Ovo pravilo važi za koordinate uređaja koje korisnik prosledi sledećim
operacijama:

- optimizacija spiska prema lokaciji;
- pretraga prodavnica u radijusu;
- postojeća pretraga najbližih lokacija lanca.

Koordinate prodavnica su poslovni podaci i nisu obuhvaćene ovim pravilom.

## Prikupljanje i svrha

Precizna lokacija se obrađuje samo posle jasne radnje korisnika kojom pokreće
jednu od navedenih operacija. Backend ne traži lokaciju u pozadini i ne koristi
je za drugu svrhu. Klijent treba da objasni svrhu pre traženja dozvole uređaja i
da omogući ručni unos polazne lokacije kada je to moguće.

## Retention

Retention oznaka je `REQUEST_ONLY`:

- latitude i longitude postoje samo u memoriji tokom jednog HTTP zahteva;
- ne upisuju se u PostgreSQL, keš, audit događaj, analitiku ili red poruka;
- ne vezuju se za `clientToken`, spisak ili budući korisnički nalog;
- posle odgovora se ne mogu ponovo koristiti bez novog zahteva korisnika.

Zbog toga nema perioda čuvanja ni korisničke operacije brisanja za precizne
koordinate: backend nema trajnu kopiju koju bi mogao da obriše.

### Izuzetak: pretraga i Akcije (`ROUNDED_1KM_5MIN`, od 8.11.2026)

Pretraga proizvoda (`SEARCH_RANKING`) i Akcije (`NEARBY_SALES`) idu kroz
`PreciseLocationPolicy.useRoughlyForAWhile`:

- server koordinate odmah zaokruži na dve decimale (oko 1 km), pa ni
  operacija ni keš nikad ne vide tačnu lokaciju;
- zaokruženo mesto sme da bude deo ključa deljenog keša odgovora
  (`CatalogMemo.rememberShared`) najviše 5 minuta; ključ nema `clientToken`,
  uređaj, nalog ni spisak, pa isti odgovor dobija svako ko isto traži iz
  istog kraja;
- keš je samo u memoriji procesa; svakog minuta se brišu odgovori stariji od
  5 minuta, a uvoz cena, nova lista akcija ili prijava „nema u prodavnici“
  brišu sve odmah;
- u log ide samo svrha, oznaka `ROUNDED_1KM_5MIN` i ishod.

Stranica `/privatnost` to kaže rečima „zaokruženo na 1 km, čuva se 5 minuta“.

## Logovanje

Aplikacija sme da zabeleži samo:

- svrhu (`PreciseLocationPurpose`);
- retention oznaku `REQUEST_ONLY`;
- ishod `SUCCESS` ili `REJECTED`.

Zabranjeno je logovati same koordinate, izvedenu adresu, ceo query string,
request body, provider zahtev/odgovor ili identifikator koji bi događaj povezao
sa konkretnim korisnikom.

Tomcat access log je isključen. Ako se kasnije uključi, konfigurisan obrazac ne
sadrži query string ni IP adresu. Reverse proxy, API gateway i cloud load
balancer moraju takođe da beleže samo putanju bez query stringa i ne smeju da
snimaju telo zahteva. Produkcioni saobraćaj mora koristiti TLS.

## Routing provider-i

`RouteMatrixProvider` dobija koordinate samo za upravo zatraženi obračun. Svaki
budući spoljni provider mora da podrži isto request-only pravilo i isključeno
logovanje koordinata. Pre povezivanja providera koji šalje podatke trećoj strani
potrebna je posebna odluka o ugovoru, regionu obrade i retention pravilima tog
providera.

OSRM adapter je zato podrazumevano isključen. Njegov keš prihvata samo parove
javnih `STORE:*` tačaka; nijedan par koji sadrži `USER` ne ulazi u keš. Precizna
polazna lokacija se šalje provideru samo tokom aktivnog zahteva i ne loguje se.

## Provera

`PreciseLocationPolicyTest` proverava da zajednička privacy granica nema
promenljivo stanje za zadržavanje lokacije i da ni uspešan ni odbijen zahtev ne
upisuju precizne koordinate u aplikacioni log.
