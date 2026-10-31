-- Cena kilometra po nalogu, na zahtev 31.10.2026.:
--
-- Uz sat u putu i stajanje (V138) kupac sada sam kaže i koliko ga košta
-- kilometar kolima (gorivo, auto koji troši više ili manje). Iznos je u
-- valuti tržišta naloga; prazno znači da važi cena tržišta (Srbija: 20 RSD),
-- pa nalog bez podešavanja dobija isti plan kao do sada. Peške kilometar i
-- dalje ne košta ništa.

ALTER TABLE app.account
    ADD COLUMN travel_cost_per_km NUMERIC(12, 2),
    ADD CONSTRAINT chk_account_travel_cost_per_km
        CHECK (travel_cost_per_km >= 0);

COMMENT ON COLUMN app.account.travel_cost_per_km IS
    'Koliko nalog računa kilometar kolima, u valuti tržišta naloga; NULL je '
    'market.travel_cost_per_km (V141).';
