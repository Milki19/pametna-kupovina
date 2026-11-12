-- Dve sitnice pre sedmog testa (12.11.2026).
--
-- 1. Broj kesa kako ga radnje pišu. V166 je kesu brojala samo iz „20/1“ i
--    „10 kom“, pa je na kopiji baze 277 kesa ostalo bez veličine, iako naziv
--    kaže koliko ih je:
--      „KESA ZA SMECE U ROLNI SA MIRISOM LIMUNA 35L/15“   15 (litre/broj)
--      „FINO KESE ZA SMECE GREEN LIFE EP 60L18K“          18 („K“ = komada)
--      „Fino kese za smece easy pack 60l 20+4“            24 (gratis kese)
--      „KESE ZA SMEĆE FINO 40L 151 U BOJI“                15 („15/1“ bez „/“)
--      „FINO KESE ZA ZAMRZIVAC 1/50 3L“, „1KG.50/1“       50
--      „Kese za zamrzivac 1kg #50“, „3L60#FOKA“            50, 60
--    a „15+3 KOM“ je davalo 3 umesto 18, „6lit/50kom“ i „60L /10KOM“ ništa.
--
--    Jedan izraz to ne može: „15+3“ se sabira, a „35L/30 50#“ mora da da 30,
--    ne 50. Zato pravilo kese sada ima više izraza po redu (nova tabela
--    app.capacity_count_pattern): prvi izraz koji da broj veći od nule
--    odlučuje, a broj je zbir uhvaćenih grupa. Izrazi su redovi po pravilu
--    (jezik, tržište), pa drugo tržište dodaje svoje bez izmene funkcije.
--    Kolona capacity_rule.count_pattern prelazi u tabelu i briše se.
--
--    Začin u zip kesi („ZACIN "C" 250G KESE ZIP30#“) nije kesa: ide u
--    isključenja i vraća svoje grame.
--
-- 2. Margarin „dijet“. V162 šalje stoni, light i namaze na kraj (biraju se
--    samo kad radnja nema drugi margarin), ali ne i dijetalni. Jedna stanica
--    je zato za „margarin za kolače“ birala „DOBRO JUTRO margarin dijet“
--    iako IDEA ima Dijamant i Vital u kocki (provera uživo 11.11.).

-- 1. Kese ------------------------------------------------------------------

CREATE TABLE app.capacity_count_pattern (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    capacity_rule_id BIGINT NOT NULL,
    -- Manji broj se pita prvi.
    priority SMALLINT NOT NULL,
    -- Nad LOWER(name), jer normalizacija briše „/“, „+“ i „#“. Broj kesa je
    -- zbir uhvaćenih grupa („20+4“ = 24); grupa koja nije uhvaćena se ne broji.
    pattern TEXT NOT NULL,
    -- Naziv iz kataloga koji izraz čita, za onoga ko menja pravilo.
    example TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_capacity_count_pattern_rule
        FOREIGN KEY (capacity_rule_id) REFERENCES app.capacity_rule (id) ON DELETE CASCADE,
    CONSTRAINT uq_capacity_count_pattern UNIQUE (capacity_rule_id, priority),
    CONSTRAINT chk_capacity_count_pattern CHECK (BTRIM(pattern) <> '')
);

COMMENT ON TABLE app.capacity_count_pattern IS
    'Kako naziv kese kaže broj kesa, po redu; prvi izraz sa brojem odlučuje (V171).';

-- Pravila drugih tržišta (ako ih ima) zadržavaju svoj izraz kao jedini.
INSERT INTO app.capacity_count_pattern (capacity_rule_id, priority, pattern, example)
SELECT rule.id, 100, rule.count_pattern, '(iz capacity_rule.count_pattern, V166)'
FROM app.capacity_rule AS rule
WHERE NOT (rule.market_id IS NULL AND rule.language = 'sr-Latn');

INSERT INTO app.capacity_count_pattern (capacity_rule_id, priority, pattern, example)
SELECT rule.id, pattern.priority, pattern.pattern, pattern.example
FROM app.capacity_rule AS rule
CROSS JOIN (
    VALUES
        -- „20/1“, „15 / 1“, „8/1LUMAP“, „1KG.50/1“; „1 0/1“ daje 0, pa dalje.
        (10, '(?:^|[^0-9,])([0-9]{1,4})\s*/\s*1(?![0-9])',
            'KESE ZA ZAMRZIVAC 1KG.50/1 LINEA'),
        -- Gratis kese se sabiraju: „20+4“, „15+3 KOM“.
        (20, '(?:^|[^0-9.,])([0-9]{1,3})\s*\+\s*([0-9]{1,3})(?![0-9])',
            'Fino kese za smece easy pack 60l 20+4'),
        -- Reč za komad: „10kom“, „/10KOM“, „6lit/50kom“, „15Stk.“, „20kesa“,
        -- „60L18K“, „20 K“ (razmak samo uz broj do 99: „153 K“ nije jasno).
        (30, '(?:^|[^0-9,])([0-9]{1,4})\s*(?:kom\M|kom\.|komad[a-z]*|pcs\M|stk\M|stk\.|kesa\M|kese\M|kesic[a-z]*)'
                || '|(?:^|[^0-9,])([0-9]{1,3})k\M'
                || '|(?:^|[^0-9,])([0-9]{1,2})\s+k\M',
            'FINO KESE ZA SMECE GREEN LIFE EP 60L18K'),
        -- Obrnuto pakovanje: „1/50“, „1/10“, „1/12“.
        (40, '(?:^|[^0-9,.])1\s*/\s*([0-9]{2,4})(?![0-9])',
            'FINO KESE ZA ZAMRZIVAC 1/50 3L'),
        -- Zapremina pa broj: „35L/15“, „35 L/15“, „2L/50“, „35l/30-1102“.
        (50, '[0-9]\s*(?:l|lit|litar[a-z]*|kg)\s*/\s*([0-9]{1,3})(?![0-9])',
            'KESA ZA SMECE U ROLNI SA MIRISOM LIMUNA 35L/15'),
        -- Broj sa „#“: „10#“, „3L60#FOKA“, „#50 Kafatex“.
        (60, '(?:^|[^0-9,])([0-9]{1,3})\s*#|#\s*([0-9]{1,3})(?![0-9])',
            'Kese za zamrzivac 1kg #50 Kafatex'),
        -- „15/1“ zapisano bez „/“: samostalno „151“, „101“, „301“, „81“, a ne
        -- „21 l“ ni šifra artikla (4 i više cifara).
        (70, '(?:^|\s)([1-9][0-9]?)1(?!\s*(?:l|lit[a-z]*|litar[a-z]*|kg|g|ml|cm|mm|x)\M)(?=\s|$)',
            'KESE ZA SMEĆE FINO 40L 151 U BOJI'),
        -- Broj na kraju, posle zapremine: „FLEXY 70L 10“.
        (80, '[0-9]\s*(?:l|kg)\s+([0-9]{1,2})$',
            'Vortex kese za smeće FLEXY 70L 10')
) AS pattern (priority, pattern, example)
WHERE rule.market_id IS NULL
  AND rule.language = 'sr-Latn';

ALTER TABLE app.capacity_rule DROP COLUMN count_pattern;

-- Začin u zip kesi nije kesa.
UPDATE app.capacity_rule
SET exclude_pattern = REPLACE(exclude_pattern, '|brasno|secer|', '|brasno|secer|zacin[a-z]*|'),
    updated_at = NOW()
WHERE market_id IS NULL
  AND language = 'sr-Latn'
  AND exclude_pattern LIKE '%|brasno|secer|%'
  AND exclude_pattern NOT LIKE '%zacin%';

CREATE OR REPLACE FUNCTION app.read_capacity_as_count(target_retailer_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    DROP TABLE IF EXISTS pg_temp.capacity_product;

    CREATE TEMP TABLE capacity_product ON COMMIT DROP AS
    SELECT product.id,
           product.canonical_product_id,
           (
               SELECT counted.bag_count
               FROM app.capacity_count_pattern AS way
               CROSS JOIN LATERAL (
                   SELECT SUM(part::INTEGER) AS bag_count
                   FROM UNNEST(REGEXP_MATCH(LOWER(product.name), way.pattern)) AS part
               ) AS counted
               WHERE way.capacity_rule_id = rule.id
                 AND way.active
                 AND counted.bag_count > 0
               ORDER BY way.priority, way.id
               LIMIT 1
           ) AS bag_count
    FROM app.retailer_product AS product
    JOIN app.retailer AS chain ON chain.id = product.retailer_id
    JOIN app.market AS market ON market.id = chain.market_id
    CROSS JOIN LATERAL (
        SELECT candidate.id
        FROM app.capacity_rule AS candidate
        WHERE candidate.active
          AND (candidate.market_id = chain.market_id
               OR (candidate.market_id IS NULL AND candidate.language = market.default_language))
          AND product.normalized_name ~ candidate.include_pattern
          AND (candidate.exclude_pattern IS NULL
               OR product.normalized_name !~ candidate.exclude_pattern)
        -- Red tržišta ima prednost nad redom jezika.
        ORDER BY candidate.market_id NULLS LAST, candidate.id
        LIMIT 1
    ) AS rule
    WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
      AND product.normalized_name IS NOT NULL;

    -- Kesa je broj kesa, ili bez veličine kad naziv ne kaže koliko ih je.
    -- Pakovanje „N/1“ je već broj kesa, pa nije pakovanje jedne kese.
    UPDATE app.retailer_product AS product
    SET quantity_value = bag.bag_count,
        base_unit = CASE WHEN bag.bag_count IS NULL THEN NULL ELSE 'piece' END,
        package_count = 1,
        package_unit_product_id = NULL
    FROM capacity_product AS bag
    WHERE bag.id = product.id
      AND (
          product.quantity_value IS DISTINCT FROM bag.bag_count
          OR product.base_unit IS DISTINCT FROM CASE WHEN bag.bag_count IS NULL THEN NULL ELSE 'piece' END
          OR product.package_count <> 1
          OR product.package_unit_product_id IS NOT NULL
      );

    -- Spojeni proizvod (isti barkod) prati svoje kese kad se slažu, i kad
    -- nijedna nema veličinu (refresh_package_sizes ovo radi samo za kese
    -- koje imaju veličinu).
    UPDATE app.canonical_product AS canonical
    SET quantity_value = agreed.quantity_value,
        base_unit = agreed.base_unit,
        updated_at = NOW()
    FROM (
        SELECT product.canonical_product_id,
               MIN(product.quantity_value) AS quantity_value,
               MIN(product.base_unit) AS base_unit
        FROM app.retailer_product AS product
        WHERE product.canonical_product_id IN (
            SELECT bag.canonical_product_id
            FROM capacity_product AS bag
            WHERE bag.canonical_product_id IS NOT NULL
        )
        GROUP BY product.canonical_product_id
        HAVING COUNT(DISTINCT product.quantity_value) <= 1
           AND COUNT(DISTINCT product.base_unit) <= 1
           AND (COUNT(product.quantity_value) = 0 OR COUNT(product.quantity_value) = COUNT(*))
    ) AS agreed
    WHERE agreed.canonical_product_id = canonical.id
      AND (
          canonical.quantity_value IS DISTINCT FROM agreed.quantity_value
          OR canonical.base_unit IS DISTINCT FROM agreed.base_unit
      );
END;
$$;

COMMENT ON FUNCTION app.read_capacity_as_count(BIGINT) IS
    'Kese: kg ili l u nazivu je nosivost; veličina je broj kesa po izrazima '
    'app.capacity_count_pattern, ili ništa (V166, V171).';

DROP TABLE IF EXISTS pg_temp.capacity_before;
CREATE TEMP TABLE capacity_before AS
SELECT product.id, product.retailer_id, product.quantity_value, product.base_unit, product.package_count
FROM app.retailer_product AS product;

-- Začin koji je V166 uzela za kesu vraća grame iz naziva; sledeći uvoz bi
-- ih vratio i sam (parser čita naziv, a kesa ga više ne menja).
UPDATE app.retailer_product AS product
SET quantity_value = (REGEXP_MATCH(LOWER(product.name), '(?:^|[^0-9,.])([0-9]{1,4})\s*(?:g|gr)\M'))[1]::NUMERIC,
    base_unit = 'g',
    package_count = 1
WHERE product.normalized_name ~ '\mzacin[a-z]*\M'
  AND product.normalized_name ~ '\m(kes[a-z]*|vrec[a-z]*)\M'
  AND (product.base_unit IS NULL OR product.base_unit = 'piece')
  AND LOWER(product.name) ~ '(?:^|[^0-9,.])[0-9]{1,4}\s*(?:g|gr)\M';

SELECT app.read_capacity_as_count(NULL);

-- Veličina je deo ključa porodice (V71): lanci čije su se kese promenile
-- grupišu se ponovo, kao posle uvoza (kao V166).
DO $$
DECLARE
    changed_retailer BIGINT;
BEGIN
    FOR changed_retailer IN
        SELECT DISTINCT product.retailer_id
        FROM app.retailer_product AS product
        JOIN capacity_before AS before ON before.id = product.id
        WHERE product.quantity_value IS DISTINCT FROM before.quantity_value
           OR product.base_unit IS DISTINCT FROM before.base_unit
           OR product.package_count IS DISTINCT FROM before.package_count
        ORDER BY 1
    LOOP
        PERFORM app.assign_product_families(changed_retailer);
    END LOOP;
END $$;

DROP TABLE capacity_before;

-- 2. Margarin „dijet“ --------------------------------------------------------

-- Dijetalni margarin ide na kraj kao light: bira se samo kad radnja nema drugi.
UPDATE app.shopping_intent_alias AS alias
SET uncommon_name_pattern = REPLACE(alias.uncommon_name_pattern, 'light|lajt|', 'light|lajt|dijet[a-z]*|diet[a-z]*|')
FROM app.shopping_intent AS intent
WHERE intent.id = alias.shopping_intent_id
  AND intent.code = 'MARGARINE'
  AND alias.uncommon_name_pattern LIKE '%light|lajt|%'
  AND alias.uncommon_name_pattern NOT LIKE '%dijet%';

-- „stoni margarin“ traži stoni; ni tu dijetalni nije prvi izbor.
UPDATE app.shopping_intent_alias AS alias
SET uncommon_name_pattern = '\m(dijet[a-z]*|diet[a-z]*)\M'
FROM app.shopping_intent AS intent
WHERE intent.id = alias.shopping_intent_id
  AND intent.code = 'MARGARINE'
  AND alias.uncommon_name_pattern IS NULL;
