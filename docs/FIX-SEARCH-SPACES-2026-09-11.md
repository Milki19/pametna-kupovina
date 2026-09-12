# Razmaci pri unosu zamene — 11.09.2026.

- Uzrok: `ProductSearchViewModel.updateQuery` je vraćao `query.trim()` u UI stanje. Dijalog za zamenu koristi to stanje kao vrednost polja, pa je razmak na kraju svake ukucane reči odmah nestajao.
- Ispravka: UI zadržava originalni unos. Minimalna dužina proverava se na trimovanom tekstu, a postojeći `ShoppingRepository.searchProducts` trimuje samo odlazni API upit. Rezultat pretrage, retry, filter i paginacija čuvaju originalni tekst polja. Greške starih zahteva proveravaju i stanje filtera.
- Test na prethodno instaliranoj verziji pokazao je `EditableText='mleko'` nakon unosa `mleko `.
- Novi test koristi stvarni ViewModel i dijalog sa lažnim API-jem i odvojenom in-memory bazom: pojedinačan space, više reči, vodeći/završni razmaci, odgovor pretrage, retry, filter, paginacija i unos samo razmaka. API dobija trimovan tekst, polje ostaje nepromenjeno.
- Ispravljena verzija: 37 unit i 9 selektivnih instrumentation testova uspešno. Backend nije menjan niti ponovo testiran u ovoj turi.
- Instalirano preko postojeće aplikacije, bez deinstalacije. Rezervna kopija: `infra/backups/android-pre-search-spaces-20260911.tar`. Pre/posle poređenje svih redova kupovina i spiska identično: 11 kupovina, zbir kupljenih stavki 4, 7 stavki spiska. Aplikacija ponovo otvorena.
- Slike potvrđuju plan sa Lidl + Maxi i uključeno izabrano Pilos mleko. Cenovnik je i dalje od 09.09.2026; automatsko osvežavanje nije uključeno.
- Preostalo: bezbedan uvoz sa validacijom i atomarnim prelaskom na novi cenovnik, zatim automatsko osvežavanje i novi lanci. Dodatno podesiti podrazumevane količine/kategorije — za generički hleb trenutno je izabran mini baget od 110 g. To nije menjano ovom ispravkom.
