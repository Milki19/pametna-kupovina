-- Kesa nije teška koliko piše na njoj (poznata sitnica posle šestog testa,
-- 11.11.2026). „FINO KESE ZAMRZIVAC 1kg“ je kesa koja nosi kilogram, a
-- katalog ju je čitao kao 1 kg robe. Isto je bilo sa kesama za smeće:
-- „KESE ZA SMEĆE TYTAN FLEX 60L 10KOM FINO“ je bilo 60 l, a ne deset kesa,
-- a „KESE ZA ZAMRZIVAČ ZIPER FINO 1L 20/1“ litar, a ne dvadeset kesa. Kesa
-- se tako poredila sa drugim kesama po kilogramu ili litru i grupisala po
-- veličini koja nije njena, a spisak je pisao „1 kg“ uz kese.
--
-- Kilogram ili litar na kesi je nosivost (zapremina), ne sadržaj. Kese se
-- broje u komadima (V162): „20/1“ i „10 kom“ su dvadeset i deset kesa, a
-- kesa bez broja nema veličinu, kao proizvod bez količine u nazivu.
--
-- Ko je kesa, kaže naziv: reč za kesu (kesa, kese, vreće, vrećice, bag)
-- odmah uz namenu (za zamrzivač, za smeće, za đubre, zip, za sendvič...).
-- Hrana u kesi to nije: „MLEKO 1L KESA“, „FRISKIES KESICA 85G“ i „GRAŠAK
-- ZA ZAMRZIVAČ 1KG“ ostaju na svojoj masi. Pravilo je red u tabeli po
-- jeziku (i po želji po tržištu), kao vrste (V162): drugo tržište dodaje
-- svoje reči bez izmene funkcije.
--
-- Čitanje radi posle svakog uvoza, na kraju app.refresh_package_sizes, pre
-- grupisanja po veličini (ProductCatalogMaintenanceService). Uvoz vrati
-- veličinu kakvu parser pročita iz naziva, pa je ponovno čitanje isto.

CREATE TABLE app.capacity_rule (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id SMALLINT,
    language VARCHAR(35) NOT NULL,
    -- Nad normalized_name (latinica, mala slova, bez kvačica i interpunkcije).
    include_pattern TEXT NOT NULL,
    exclude_pattern TEXT,
    -- Nad LOWER(name), jer normalizacija briše „/“ iz „20/1“. Prva grupa je
    -- broj komada u pakovanju.
    count_pattern TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_capacity_rule_market
        FOREIGN KEY (market_id) REFERENCES app.market (id),
    CONSTRAINT chk_capacity_rule_language CHECK (BTRIM(language) <> ''),
    CONSTRAINT chk_capacity_rule_include CHECK (BTRIM(include_pattern) <> ''),
    CONSTRAINT chk_capacity_rule_count CHECK (BTRIM(count_pattern) <> '')
);

CREATE UNIQUE INDEX uq_capacity_rule
    ON app.capacity_rule (COALESCE(market_id, 0), language, include_pattern);

COMMENT ON TABLE app.capacity_rule IS
    'Proizvodi čiji kg ili l u nazivu je nosivost, a ne sadržaj (kese), po jeziku (V166).';

INSERT INTO app.capacity_rule (market_id, language, include_pattern, exclude_pattern, count_pattern)
VALUES (
    NULL,
    'sr-Latn',
    -- Reč za kesu odmah uz namenu („kese za zamrzivac“, „fino kese
    -- zamrzivac“, „kese smece“, „vrece za djubre“), ili zip ispred kese.
    '\m(kes[a-z]*|vrec[a-z]*|vreck[a-z]*|bag|bags)( za)? '
        || '(zamrziv[a-z]*|zamrzavanj[a-z]*|freezer|smec[a-z]*|djubr[a-z]*|dj|otpad[a-z]*'
        || '|recikl[a-z]*|zip|ziper|zipper|ziploc|ziplock|sendvic[a-z]*|cuvanj[a-z]*|trash|garbage)\M'
        || '|\m(zip|ziper|zipper|ziploc|ziplock|freezer|trash|garbage) (kes[a-z]*|vrec[a-z]*|bag|bags)\M',
    -- Hrana koju lanac zove po zamrzivaču ili pakuje u kesu nije kesa.
    '\m(povrc[a-z]*|grasak|pomfrit[a-z]*|krompir[a-z]*|riba|ribe|meso|mesa|piletin[a-z]*|sladoled[a-z]*'
        || '|pica|pizza|zamrznut[a-z]*|smrznut[a-z]*|led|leda|kock[a-z]*|mleko|mlijeko|jogurt[a-z]*'
        || '|brasno|secer|pirinac|pasulj)\M',
    -- „20/1“, „15 / 1“, „10kom“, „10 komada“, „40 pcs“; ne „60l“ ni „1kg“.
    '(?:^|[^0-9/.,])([0-9]{1,4})\s*(?:/\s*1(?![0-9])|kom\M|kom\.|komad[a-z]*|pcs\M|kesa\M|kese\M|kesic[a-z]*)'
);

-- Kese iz naziva -------------------------------------------------------------

CREATE FUNCTION app.read_capacity_as_count(target_retailer_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    DROP TABLE IF EXISTS pg_temp.capacity_product;

    CREATE TEMP TABLE capacity_product ON COMMIT DROP AS
    SELECT product.id,
           product.canonical_product_id,
           NULLIF((REGEXP_MATCH(LOWER(product.name), rule.count_pattern))[1]::INTEGER, 0) AS bag_count
    FROM app.retailer_product AS product
    JOIN app.retailer AS chain ON chain.id = product.retailer_id
    JOIN app.market AS market ON market.id = chain.market_id
    CROSS JOIN LATERAL (
        SELECT candidate.count_pattern
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
    'Kese: kg ili l u nazivu je nosivost; veličina je broj kesa ili ništa (V166).';

-- Čitanje veličina iz naziva (V71, V73, V76) ostaje kakvo je, pod novim
-- imenom; app.refresh_package_sizes ga zove pa čita kese.
ALTER FUNCTION app.refresh_package_sizes(BIGINT) RENAME TO read_package_sizes_from_names;

CREATE FUNCTION app.refresh_package_sizes(target_retailer_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM app.read_package_sizes_from_names(target_retailer_id);
    PERFORM app.read_capacity_as_count(target_retailer_id);
END;
$$;

-- Kese koje su već u katalogu ---------------------------------------------

DROP TABLE IF EXISTS pg_temp.capacity_before;
CREATE TEMP TABLE capacity_before AS
SELECT product.id, product.retailer_id, product.quantity_value, product.base_unit, product.package_count
FROM app.retailer_product AS product;

SELECT app.read_capacity_as_count(NULL);

-- Veličina je deo ključa porodice (V71), pa se lanci sa kesama čije su se
-- veličine promenile grupišu ponovo, kao posle uvoza.
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
