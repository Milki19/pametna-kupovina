-- Cena vremena i stajanja, na zahtev 18.10.2026.:
--
-- Plan je vreme u putu računao po ceni tržišta (Srbija: 400 RSD po satu) i
-- svako stajanje po 80 RSD. Kome je sat vredniji ili jeftiniji, sam to
-- podesi. Iznos je u valuti tržišta naloga; prazno znači da važi cena
-- tržišta, pa nalog bez podešavanja dobija isti plan kao do sada, a tržište
-- koje promeni svoju cenu menja je i svima koji je nisu dirali.

ALTER TABLE app.account
    ADD COLUMN value_per_hour NUMERIC(12, 2),
    ADD COLUMN cost_per_stop NUMERIC(12, 2),
    ADD CONSTRAINT chk_account_trip_costs
        CHECK (value_per_hour >= 0 AND cost_per_stop >= 0);

COMMENT ON COLUMN app.account.value_per_hour IS
    'Koliko nalogu vredi sat u putu, u valuti tržišta naloga; NULL je '
    'market.value_per_hour (V138).';
COMMENT ON COLUMN app.account.cost_per_stop IS
    'Koliko nalog računa jedno stajanje, u valuti tržišta naloga; NULL je '
    'market.cost_per_stop (V138).';
