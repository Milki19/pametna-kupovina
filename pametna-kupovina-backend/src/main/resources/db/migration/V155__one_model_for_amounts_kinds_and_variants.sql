-- Ugovor modela (šesti test, 6.11.2026). Kolega je našao tri greške koje
-- nisu greške jedne reči nego modela: komad je postao kilogram („limun 3“ =
-- 3 kg), vrsta se nalazila po početku reči i bez varijante (kinoa → Kinder,
-- Nespresso kapsule → mlevena kafa), a hod se nije računao isto u svim
-- planovima. Ova migracija samo postavlja oblik podataka: prazne tabele,
-- kolone bez vrednosti i jednu funkciju koja čita ono što baza već zna.
-- Ponašanje se ne menja. Niti talasa 1 (S1–S7) pune ove tabele i pišu
-- pravila nad njima, svaka u svojoj migraciji od V156 naviše.
--
-- Sve što zavisi od jezika ili zemlje nosi tržište (market_id) i jezik (BCP
-- 47, kao app.market.default_language). Red bez tržišta važi za sva tržišta
-- tog jezika, a red tržišta ima prednost nad njim.

-- 1. Količina -----------------------------------------------------------------
--
-- Stavka spiska je {broj, jedinica, broj pakovanja}: „limun 3“ je 3 piece,
-- „2x mleko 1l“ je 1000 ml u 2 pakovanja, „toalet papir 10 rolni“ je 10
-- piece. To već čuvaju kolone shopping_list_item (target_quantity,
-- base_unit, quantity), pa nova kolona za to ne treba.

-- Rečnik jedinica, umesto regex-a u parseru. Svaki oblik reči je svoj red
-- („kesica“, „kesice“, „kesica“), jer se padeži razlikuju po jeziku.
CREATE TABLE app.unit_word (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id SMALLINT,
    language VARCHAR(35) NOT NULL,
    -- Kao ProductNameNormalizer: latinica, mala slova, bez kvačica.
    normalized_word VARCHAR(60) NOT NULL,
    -- MEASURE: masa ili zapremina (kg, l), COUNT: komad (kom),
    -- PACK: pakovanje koje se broji (kesica, rolna, kutija, flaša, limenka),
    -- MULTIPLIER: broj pakovanja ispred ili iza („3x“, „x3“, „puta“).
    role VARCHAR(20) NOT NULL,
    -- Jedinica u kojoj katalog meri: g, ml, piece. PACK i MULTIPLIER je nemaju.
    base_unit VARCHAR(20),
    -- Koliko base_unit je jedna ova jedinica: kg = 1000 g, dl = 100 ml.
    factor NUMERIC(14, 4) NOT NULL DEFAULT 1,
    priority SMALLINT NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_unit_word_market
        FOREIGN KEY (market_id) REFERENCES app.market (id),
    CONSTRAINT chk_unit_word_language CHECK (BTRIM(language) <> ''),
    CONSTRAINT chk_unit_word_word CHECK (BTRIM(normalized_word) <> ''),
    CONSTRAINT chk_unit_word_role
        CHECK (role IN ('MEASURE', 'COUNT', 'PACK', 'MULTIPLIER')),
    CONSTRAINT chk_unit_word_base_unit
        CHECK (
            -- COALESCE: a NULL unit would make the check unknown, and pass.
            (role = 'MEASURE' AND COALESCE(base_unit, '') IN ('g', 'ml'))
            OR (role = 'COUNT' AND COALESCE(base_unit, '') = 'piece')
            OR (role IN ('PACK', 'MULTIPLIER') AND base_unit IS NULL)
        ),
    CONSTRAINT chk_unit_word_factor CHECK (factor > 0),
    CONSTRAINT chk_unit_word_priority CHECK (priority > 0)
);

CREATE UNIQUE INDEX uq_unit_word
    ON app.unit_word (COALESCE(market_id, 0), language, normalized_word);

COMMENT ON TABLE app.unit_word IS
    'Rečnik jedinica po jeziku i tržištu: kg, kom, kesica, rolna, 3x (V155).';

-- Prosečna masa jednog komada: limun ~100 g, avokado ~200 g, banana ~180 g.
-- „limun 3“ se tako kupuje kao 3 komada ili ~300 g na meru, nikad 3 kg.
-- Masa ide uz vrstu proizvoda, uz reč sa spiska (shopping_intent) ili uz
-- samu reč, šta god tržište ima; tačno jedno od tri.
CREATE TABLE app.piece_mass (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id SMALLINT,
    product_type_id BIGINT,
    shopping_intent_id BIGINT,
    language VARCHAR(35),
    normalized_word VARCHAR(200),
    grams NUMERIC(10, 2) NOT NULL,
    -- Odakle je broj: „USDA 9152“, „merenje Maxi 8.11.“, „prosek 3 lanca“.
    source VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_piece_mass_market
        FOREIGN KEY (market_id) REFERENCES app.market (id),
    CONSTRAINT fk_piece_mass_type
        FOREIGN KEY (product_type_id) REFERENCES app.product_type (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_piece_mass_intent
        FOREIGN KEY (shopping_intent_id) REFERENCES app.shopping_intent (id)
            ON DELETE CASCADE,
    CONSTRAINT chk_piece_mass_one_key
        CHECK (NUM_NONNULLS(product_type_id, shopping_intent_id, normalized_word) = 1),
    CONSTRAINT chk_piece_mass_word_language
        CHECK (normalized_word IS NULL
               OR (BTRIM(normalized_word) <> '' AND language IS NOT NULL
                   AND BTRIM(language) <> '')),
    -- Od zrna do lubenice.
    CONSTRAINT chk_piece_mass_grams CHECK (grams > 0 AND grams <= 20000),
    CONSTRAINT chk_piece_mass_source CHECK (BTRIM(source) <> '')
);

CREATE UNIQUE INDEX uq_piece_mass_type
    ON app.piece_mass (COALESCE(market_id, 0), product_type_id)
    WHERE product_type_id IS NOT NULL;
CREATE UNIQUE INDEX uq_piece_mass_intent
    ON app.piece_mass (COALESCE(market_id, 0), shopping_intent_id)
    WHERE shopping_intent_id IS NOT NULL;
CREATE UNIQUE INDEX uq_piece_mass_word
    ON app.piece_mass (COALESCE(market_id, 0), language, normalized_word)
    WHERE normalized_word IS NOT NULL;

COMMENT ON TABLE app.piece_mass IS
    'Prosečna masa jednog komada, po vrsti, reči sa spiska ili reči (V155).';

-- Način prodaje proizvoda u lancu: PIECE (komad, cena po komadu),
-- LOOSE_KG (rinfuz, cena po kg), PACKAGE (pakovanje sa neto masom ili
-- zapreminom: quantity_value × package_count u base_unit). Baza ovo već
-- zna iz sale_unit (V72) i veličine pakovanja (V14, V73), pa
-- app.sale_mode_of čita to; kolona sale_mode je samo ručna ispravka kad
-- lanac piše pogrešno (Maxi „Mango komad“ sa jedinicom kg).
ALTER TABLE app.retailer_product
    ADD COLUMN sale_mode VARCHAR(20);

ALTER TABLE app.retailer_product
    ADD CONSTRAINT chk_retailer_product_sale_mode
        CHECK (sale_mode IS NULL OR sale_mode IN ('PIECE', 'LOOSE_KG', 'PACKAGE'));

COMMENT ON COLUMN app.retailer_product.sale_mode IS
    'Ručna ispravka načina prodaje; inače važi app.sale_mode_of (V155).';

-- Isto pravilo kao SaleMode.of u Javi; test SaleModeTest drži ih istim.
CREATE OR REPLACE FUNCTION app.sale_mode_of(
    sale_mode_override VARCHAR,
    sale_unit VARCHAR,
    quantity_value NUMERIC,
    base_unit VARCHAR
)
RETURNS VARCHAR
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN sale_mode_override IS NOT NULL THEN sale_mode_override
        WHEN sale_unit = 'kg' THEN 'LOOSE_KG'
        WHEN quantity_value IS NOT NULL AND base_unit IN ('g', 'ml') THEN 'PACKAGE'
        WHEN sale_unit = 'kom' OR base_unit = 'piece' THEN 'PIECE'
        ELSE NULL
    END
$$;

-- 2. Vrsta, osnovna vrsta i varijanta ------------------------------------------
--
-- osnovna vrsta (riba) → vrsta (oslić, skuša, brancin) → obavezne osobine
-- (oblik: file, smrznuto; varijanta: zero, kapsule). Medijana cene za
-- „riba“ se računa po osnovnoj vrsti, a ne po najširoj reči (S6).

ALTER TABLE app.product_type
    ADD COLUMN base_type_id BIGINT;

ALTER TABLE app.product_type
    ADD CONSTRAINT fk_product_type_base
        FOREIGN KEY (base_type_id) REFERENCES app.product_type (id),
    ADD CONSTRAINT chk_product_type_base_not_self
        CHECK (base_type_id IS NULL OR base_type_id <> id);

CREATE INDEX idx_product_type_base
    ON app.product_type (base_type_id)
    WHERE base_type_id IS NOT NULL;

COMMENT ON COLUMN app.product_type.base_type_id IS
    'Osnovna vrsta (riba za oslić). Samo jedan nivo: osnovna vrsta nema svoju (V155).';

-- Samo jedan nivo, da medijana osnovne vrste ne zavisi od dubine stabla.
CREATE OR REPLACE FUNCTION app.product_type_base_is_one_level()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.base_type_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM app.product_type
        WHERE id = NEW.base_type_id AND base_type_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Osnovna vrsta % ima svoju osnovnu vrstu', NEW.base_type_id;
    END IF;
    IF NEW.base_type_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM app.product_type WHERE base_type_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Vrsta % je osnovna vrsta drugima i ne može imati svoju', NEW.id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_product_type_base_one_level
    BEFORE INSERT OR UPDATE OF base_type_id ON app.product_type
    FOR EACH ROW EXECUTE FUNCTION app.product_type_base_is_one_level();

-- Osobine koje vrsta mora da ima zapisane da bi proizvod bio izbor kad ih
-- spisak traži: kafa → oblik (kapsule, zrno, mlevena), gazirani sok →
-- šećer (zero, light, obično). Vrednosti su u retailer_product_attribute.
CREATE TABLE app.product_type_required_attribute (
    product_type_id BIGINT NOT NULL,
    attribute_definition_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (product_type_id, attribute_definition_id),

    CONSTRAINT fk_type_required_attribute_type
        FOREIGN KEY (product_type_id) REFERENCES app.product_type (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_type_required_attribute_definition
        FOREIGN KEY (attribute_definition_id)
            REFERENCES app.product_attribute_definition (id)
);

COMMENT ON TABLE app.product_type_required_attribute IS
    'Obavezne osobine vrste: oblik kafe, šećer u soku (V155).';

-- Reč sa spiska koja je varijanta: „zero“ → šećer = ZERO, „kapsule“ →
-- oblik = CAPSULES, „smrznut“ → stanje = FROZEN. Takva reč postaje obavezan
-- filter, a ne samo reč koja podiže rang. Poklapa se cela reč (WholeWords).
CREATE TABLE app.variant_word (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id SMALLINT,
    language VARCHAR(35) NOT NULL,
    normalized_word VARCHAR(100) NOT NULL,
    attribute_definition_id BIGINT NOT NULL,
    -- Vrednost osobine kako je piše retailer_product_attribute.text_value.
    text_value VARCHAR(200) NOT NULL,
    priority SMALLINT NOT NULL DEFAULT 100,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_variant_word_market
        FOREIGN KEY (market_id) REFERENCES app.market (id),
    CONSTRAINT fk_variant_word_attribute
        FOREIGN KEY (attribute_definition_id)
            REFERENCES app.product_attribute_definition (id),
    CONSTRAINT chk_variant_word_language CHECK (BTRIM(language) <> ''),
    CONSTRAINT chk_variant_word_word CHECK (BTRIM(normalized_word) <> ''),
    CONSTRAINT chk_variant_word_value CHECK (BTRIM(text_value) <> ''),
    CONSTRAINT chk_variant_word_priority CHECK (priority > 0)
);

CREATE UNIQUE INDEX uq_variant_word
    ON app.variant_word (COALESCE(market_id, 0), language, normalized_word,
                         attribute_definition_id);

COMMENT ON TABLE app.variant_word IS
    'Reči sa spiska koje su obavezna varijanta: zero, kapsule, smrznuto (V155).';

-- 3. Trošak puta ----------------------------------------------------------------
--
-- Cene puta već stoje uz tržište (app.market: travel_cost_per_km,
-- value_per_hour, cost_per_stop) i uz nalog (V109), pa tabela ne treba.
-- Jedna funkcija troška je TripCost u Javi, a jedan čuvar pravila PlanGuard.
