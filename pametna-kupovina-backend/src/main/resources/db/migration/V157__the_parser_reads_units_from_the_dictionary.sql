-- Rečnik jedinica za srpski (sr-Latn), umesto regex-a u ShoppingListTextParser
-- (šesti test, 6.11.2026, stavka 8): „toalet papir 10 rolni“, „5 kesica
-- kvasca“, „grcki jogurt 3x“ (bilo je 1 kom).
--
-- Reči su kao ProductNameNormalizer: latinica, mala slova, bez kvačica, pa
-- „flaša“, „флаша“ i „flasa“ idu na isti red. Svaki padež je svoj red.
-- Red bez tržišta važi za sve zemlje na srpskom; drugo tržište dodaje svoje
-- redove (ili jezik, za drugu zemlju), bez izmene koda.
--
-- Uloge:
--   MEASURE    masa ili zapremina, koliko ukupno: kg, g, l, dl, ml
--   COUNT      komad: kom, komad, pcs. „rolna“ je komad unutar pakovanja
--              (inside_pack): „toalet papir 10 rolni“ je jedno pakovanje od
--              10 rolni, ne 10 pakovanja.
--   PACK       pakovanje koje se broji: „5 kesica kvasca“ je 5 kesica
--   MULTIPLIER koliko pakovanja: „3x“, „x3“, „3 puta“

ALTER TABLE app.unit_word
    ADD COLUMN inside_pack BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE app.unit_word
    ADD CONSTRAINT chk_unit_word_inside_pack
        CHECK (NOT inside_pack OR role = 'COUNT');

COMMENT ON COLUMN app.unit_word.inside_pack IS
    'Komad unutar jednog pakovanja („10 rolni“): broj kaže veličinu pakovanja, ne broj pakovanja (V157).';

INSERT INTO app.unit_word (market_id, language, normalized_word, role, base_unit, factor, inside_pack)
VALUES
    -- Masa
    (NULL, 'sr-Latn', 'kg', 'MEASURE', 'g', 1000, FALSE),
    (NULL, 'sr-Latn', 'kilogram', 'MEASURE', 'g', 1000, FALSE),
    (NULL, 'sr-Latn', 'kilograma', 'MEASURE', 'g', 1000, FALSE),
    (NULL, 'sr-Latn', 'kila', 'MEASURE', 'g', 1000, FALSE),
    (NULL, 'sr-Latn', 'kile', 'MEASURE', 'g', 1000, FALSE),
    (NULL, 'sr-Latn', 'kilo', 'MEASURE', 'g', 1000, FALSE),
    (NULL, 'sr-Latn', 'kilu', 'MEASURE', 'g', 1000, FALSE),
    (NULL, 'sr-Latn', 'g', 'MEASURE', 'g', 1, FALSE),
    (NULL, 'sr-Latn', 'gr', 'MEASURE', 'g', 1, FALSE),
    (NULL, 'sr-Latn', 'gram', 'MEASURE', 'g', 1, FALSE),
    (NULL, 'sr-Latn', 'grama', 'MEASURE', 'g', 1, FALSE),
    -- Zapremina
    (NULL, 'sr-Latn', 'l', 'MEASURE', 'ml', 1000, FALSE),
    (NULL, 'sr-Latn', 'lit', 'MEASURE', 'ml', 1000, FALSE),
    (NULL, 'sr-Latn', 'litar', 'MEASURE', 'ml', 1000, FALSE),
    (NULL, 'sr-Latn', 'litra', 'MEASURE', 'ml', 1000, FALSE),
    (NULL, 'sr-Latn', 'litre', 'MEASURE', 'ml', 1000, FALSE),
    (NULL, 'sr-Latn', 'litara', 'MEASURE', 'ml', 1000, FALSE),
    (NULL, 'sr-Latn', 'dl', 'MEASURE', 'ml', 100, FALSE),
    (NULL, 'sr-Latn', 'ml', 'MEASURE', 'ml', 1, FALSE),
    (NULL, 'sr-Latn', 'mililitar', 'MEASURE', 'ml', 1, FALSE),
    (NULL, 'sr-Latn', 'mililitara', 'MEASURE', 'ml', 1, FALSE),
    -- Komad
    (NULL, 'sr-Latn', 'kom', 'COUNT', 'piece', 1, FALSE),
    (NULL, 'sr-Latn', 'komad', 'COUNT', 'piece', 1, FALSE),
    (NULL, 'sr-Latn', 'komada', 'COUNT', 'piece', 1, FALSE),
    (NULL, 'sr-Latn', 'komadi', 'COUNT', 'piece', 1, FALSE),
    (NULL, 'sr-Latn', 'pcs', 'COUNT', 'piece', 1, FALSE),
    -- Komad unutar pakovanja: rolna toalet papira ili ubrusa
    (NULL, 'sr-Latn', 'rolna', 'COUNT', 'piece', 1, TRUE),
    (NULL, 'sr-Latn', 'rolne', 'COUNT', 'piece', 1, TRUE),
    (NULL, 'sr-Latn', 'rolni', 'COUNT', 'piece', 1, TRUE),
    (NULL, 'sr-Latn', 'rolnu', 'COUNT', 'piece', 1, TRUE),
    (NULL, 'sr-Latn', 'rola', 'COUNT', 'piece', 1, TRUE),
    (NULL, 'sr-Latn', 'role', 'COUNT', 'piece', 1, TRUE),
    -- Pakovanje koje se broji
    (NULL, 'sr-Latn', 'kesica', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kesice', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kesicu', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kesici', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kutija', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kutije', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kutiju', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kutijica', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'kutijice', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'flasa', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'flase', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'flasu', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'flasica', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'flasice', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'limenka', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'limenke', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'limenku', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'limenki', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'pakovanje', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'pakovanja', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'pakovanju', 'PACK', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'pak', 'PACK', NULL, 1, FALSE),
    -- Koliko pakovanja
    (NULL, 'sr-Latn', 'x', 'MULTIPLIER', NULL, 1, FALSE),
    (NULL, 'sr-Latn', 'puta', 'MULTIPLIER', NULL, 1, FALSE)
ON CONFLICT DO NOTHING;
