-- Chains write the same product differently: another word order, "GR" for
-- "G", with or without diacritics, and a department label such as "povrće" or
-- "nema podatak" where the brand belongs. The family key compared the
-- normalized name letter for letter, so "CELER KOREN" was five products in
-- five chains and the app listed it five times.
--
-- A product is now identified by the words of its name in any order (the
-- package size taken out once the parser has read it), by its brand and by
-- that package size. A different real brand is still a different product; only
-- department labels and "no brand" markers stop counting as brands.
--
-- The rule lives in the database so that this migration and every later
-- import group products the same way. Existing families keep their ids, so
-- shopping list items that point at one keep pointing at the merged product.

CREATE OR REPLACE FUNCTION app.fold_match_text(value TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT BTRIM(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(
               TRANSLATE(
                   REPLACE(LOWER(COALESCE(value, '')), 'đ', 'dj'),
                   'čćšžäëïöüáéíóúàèìòùâêîôûãõñçå',
                   'ccszaeiouaeiouaeiouaeiouaonca'
               ),
               '[^a-z0-9]+', ' ', 'g'),
               '([a-z])([0-9])', '\1 \2', 'g'),
               '([0-9])([a-z])', '\1 \2', 'g'),
               ' +', ' ', 'g'))
$$;

-- Labels chains put in the brand column that say nothing about who made the
-- product. Everything else, including a chain's own label, stays a brand.
CREATE OR REPLACE FUNCTION app.product_match_brand_key(brand_name TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
               WHEN folded.value IN (
                   'nema podatak', 'no brand', 'brend', 'rm nije definisana',
                   'voce', 'povrce', 'suvo voce i povrce', 'sveze meso',
                   'meso i riba', 'zivina', 'ribarnica', 'pekara', 'kvantas',
                   'hleb', 'kafa', 'brasno', 'so', 'smrznuta riba',
                   'zamrznuti fileti'
               )
               -- Maxi names its counters: "maxi pijaca bulk", "maxi mesara".
               OR folded.value ~ '^maxi (pijaca|pekara|mesara|ribarnica|pakovano)( |$)'
                   THEN ''
               ELSE folded.value
           END
    FROM (SELECT app.fold_match_text(brand_name) AS value) AS folded
$$;

CREATE OR REPLACE FUNCTION app.product_match_key(
    product_name TEXT,
    brand_name TEXT,
    quantity_value NUMERIC,
    base_unit TEXT
)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH input AS (
        SELECT app.fold_match_text(product_name) AS full_name,
               -- Only a size the parser actually read is taken out: an
               -- unread "CCA1000G" and "CCA700G" must stay apart.
               app.fold_match_text(CASE
                   WHEN quantity_value IS NULL THEN product_name
                   ELSE REGEXP_REPLACE(
                       product_name,
                       '[0-9]+([.,][0-9]+)?\s*(ml|l|lit|g|gr|kg)(?![[:alpha:]])',
                       ' ',
                       'gi'
                   )
               END) AS name_without_quantity,
               app.fold_match_text(brand_name) AS brand_words,
               app.product_match_brand_key(brand_name) AS brand_key
    ), effective AS (
        SELECT full_name,
               name_without_quantity,
               brand_words,
               -- Some chains copy the product name into the brand column.
               CASE WHEN brand_key = full_name THEN '' ELSE brand_key END
                   AS brand_key
        FROM input
    ), words AS (
        SELECT STRING_AGG(word.value, ' ' ORDER BY word.value) AS joined
        FROM effective
        CROSS JOIN LATERAL UNNEST(
            STRING_TO_ARRAY(effective.name_without_quantity, ' ')
        ) AS raw(value)
        LEFT JOIN (
            VALUES
                -- Filler that says nothing about the product.
                ('stand', ''), ('var', ''), ('cca', ''), ('ca', ''),
                ('gr', ''), ('g', ''), ('ml', ''), ('l', ''), ('kg', ''),
                ('x', ''), ('i', ''), ('sa', ''), ('za', ''), ('od', ''),
                ('u', ''), ('rf', ''), ('rinfuz', ''), ('mm', ''),
                -- Shortenings and word forms found on one product in two
                -- chains, each mapped to one stem.
                ('bomb', 'bombon'), ('bombone', 'bombon'),
                ('bombona', 'bombon'),
                ('dim', 'dimljen'), ('dimlj', 'dimljen'),
                ('dimljena', 'dimljen'), ('dimljeni', 'dimljen'),
                ('dimljeno', 'dimljen'),
                ('past', 'pasteta'),
                ('pil', 'pilec'), ('pileca', 'pilec'), ('pileci', 'pilec'),
                ('pilece', 'pilec'),
                ('jun', 'junec'), ('juneca', 'junec'), ('juneci', 'junec'),
                ('junece', 'junec'),
                ('cureca', 'curec'), ('cureci', 'curec'), ('curece', 'curec'),
                ('teleca', 'telec'), ('teleci', 'telec'), ('telece', 'telec'),
                ('svinjska', 'svinjsk'), ('svinjski', 'svinjsk'),
                ('svinjsko', 'svinjsk'),
                ('bk', 'bez koske'), ('sk', 'sa koskom'),
                ('pomor', 'pomorandza'),
                ('tus', 'tusiranje'), ('tusir', 'tusiranje'),
                ('kobas', 'kobasica'),
                ('voc', 'vocn'), ('vocni', 'vocn'), ('vocna', 'vocn'),
                ('vocno', 'vocn'),
                ('jog', 'jogurt'),
                ('slad', 'sladoled'),
                ('liza', 'lizalica'),
                ('toplj', 'topljen'), ('topljeni', 'topljen'),
                ('crv', 'crven'), ('crvena', 'crven'), ('crveni', 'crven'),
                ('crveno', 'crven'), ('crvene', 'crven'),
                ('del', 'delikates'),
                ('inv', 'invisible'), ('invisib', 'invisible'),
                ('invisibl', 'invisible'),
                ('cok', 'cokolad'), ('coko', 'cokolad'),
                ('cokolada', 'cokolad'), ('cokoladna', 'cokolad'),
                ('cokoladni', 'cokolad'), ('cokoladno', 'cokolad'),
                ('cokoladne', 'cokolad')
        ) AS synonym(word, replacement)
          ON synonym.word = raw.value
        CROSS JOIN LATERAL UNNEST(
            STRING_TO_ARRAY(COALESCE(synonym.replacement, raw.value), ' ')
        ) AS word(value)
        WHERE word.value <> ''
          -- The brand is compared on its own, wherever the chain wrote it in
          -- the name and however it shortened it ("NIV", "CARNE").
          AND NOT (
              effective.brand_key <> ''
              AND EXISTS (
                  SELECT 1
                  FROM UNNEST(STRING_TO_ARRAY(
                      effective.brand_words || ' ' || effective.brand_key, ' '
                  )) AS brand_word(value)
                  WHERE brand_word.value <> ''
                    AND (
                        brand_word.value = word.value
                        OR (LENGTH(word.value) >= 3
                            AND brand_word.value LIKE word.value || '%')
                        OR (LENGTH(brand_word.value) >= 4
                            AND word.value LIKE brand_word.value || '%')
                    )
              )
          )
    )
    SELECT MD5(
               effective.brand_key || '|' ||
               CASE
                   -- Nothing left to tell products apart: never lump them.
                   WHEN COALESCE(words.joined, '') = ''
                        AND effective.brand_key = ''
                       THEN effective.full_name
                   ELSE COALESCE(words.joined, '')
               END || '|' ||
               COALESCE(TRIM_SCALE(quantity_value)::TEXT, '') || '|' ||
               COALESCE(base_unit, '')
           )
    FROM effective, words
$$;

-- Puts every product of one chain (or of all chains when the argument is NULL)
-- into the family of its match key. A key keeps the family that already
-- carries it; otherwise the oldest family whose products mostly have that key
-- takes it over, so ids survive a change of the rule.
CREATE OR REPLACE FUNCTION app.assign_product_families(target_retailer_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    DROP TABLE IF EXISTS pg_temp.family_product_key;
    DROP TABLE IF EXISTS pg_temp.family_key_owner;
    DROP TABLE IF EXISTS pg_temp.family_moved_to;

    CREATE TEMP TABLE family_product_key ON COMMIT DROP AS
    SELECT source.retailer_product_id,
           source.canonical_product_id,
           source.current_family_id,
           source.name,
           source.normalized_name,
           CASE
               WHEN app.product_match_brand_key(source.brand_name)
                    IN ('', app.fold_match_text(source.name))
                   THEN NULL
               ELSE source.brand_id
           END AS brand_id,
           source.quantity_value,
           source.base_unit,
           app.product_match_key(
               source.name,
               source.brand_name,
               source.quantity_value,
               source.base_unit
           ) AS match_key
    FROM (
        SELECT product.id AS retailer_product_id,
               product.canonical_product_id,
               product.product_family_id AS current_family_id,
               COALESCE(canonical.name, product.name) AS name,
               COALESCE(canonical.normalized_name, product.normalized_name)
                   AS normalized_name,
               brand.id AS brand_id,
               brand.normalized_name AS brand_name,
               CASE WHEN canonical.id IS NULL
                    THEN product.quantity_value
                    ELSE canonical.quantity_value
               END AS quantity_value,
               CASE WHEN canonical.id IS NULL
                    THEN product.base_unit
                    ELSE canonical.base_unit
               END AS base_unit
        FROM app.retailer_product AS product
        LEFT JOIN app.canonical_product AS canonical
          ON canonical.id = product.canonical_product_id
        LEFT JOIN app.brand AS brand
          ON brand.id = CASE WHEN canonical.id IS NULL
                             THEN product.brand_id
                             ELSE canonical.brand_id
                        END
        WHERE target_retailer_id IS NULL
           OR product.retailer_id = target_retailer_id
    ) AS source
    WHERE NULLIF(BTRIM(source.normalized_name), '') IS NOT NULL;

    ANALYZE family_product_key;

    CREATE TEMP TABLE family_key_owner ON COMMIT DROP AS
    WITH family_usage AS (
        SELECT current_family_id AS family_id,
               match_key,
               COUNT(*) AS product_count
        FROM family_product_key
        WHERE current_family_id IS NOT NULL
        GROUP BY current_family_id, match_key
    ), main_key AS (
        SELECT DISTINCT ON (family_id) family_id, match_key
        FROM family_usage
        ORDER BY family_id, product_count DESC, match_key
    ), candidate AS (
        -- One chain at a time only sees part of a family, so a family that
        -- already carries a key is never handed another one in that mode.
        SELECT family.id AS family_id,
               SUBSTRING(family.family_key FROM 4) AS match_key,
               0 AS priority
        FROM app.product_family AS family
        WHERE target_retailer_id IS NOT NULL
          AND family.family_key IN (
              SELECT 'MK:' || match_key FROM family_product_key
          )
        UNION ALL
        SELECT main_key.family_id,
               main_key.match_key,
               CASE WHEN family.family_key = 'MK:' || main_key.match_key
                    THEN 1 ELSE 2
               END
        FROM main_key
        JOIN app.product_family AS family
          ON family.id = main_key.family_id
        WHERE target_retailer_id IS NULL
           OR family.family_key NOT LIKE 'MK:%'
    )
    SELECT DISTINCT ON (match_key) match_key, family_id
    FROM candidate
    ORDER BY match_key, priority, family_id;

    -- Free every key that is about to be used by a family that will not own it.
    UPDATE app.product_family AS family
    SET family_key = 'OLD:' || family.id
    WHERE family.family_key IN (
              SELECT 'MK:' || match_key FROM family_product_key
          )
      AND NOT EXISTS (
          SELECT 1
          FROM family_key_owner AS owner
          WHERE owner.family_id = family.id
            AND 'MK:' || owner.match_key = family.family_key
      );

    UPDATE app.product_family AS family
    SET family_key = 'TMP:' || family.id
    FROM family_key_owner AS owner
    WHERE owner.family_id = family.id
      AND family.family_key <> 'MK:' || owner.match_key;

    UPDATE app.product_family AS family
    SET family_key = 'MK:' || owner.match_key,
        updated_at = NOW()
    FROM family_key_owner AS owner
    WHERE owner.family_id = family.id
      AND family.family_key <> 'MK:' || owner.match_key;

    INSERT INTO app.product_family (
        family_key,
        display_name,
        normalized_name,
        brand_id,
        quantity_value,
        base_unit,
        review_status
    )
    SELECT 'MK:' || product_key.match_key,
           MIN(product_key.name),
           MIN(product_key.normalized_name),
           MIN(product_key.brand_id),
           MIN(product_key.quantity_value),
           MIN(product_key.base_unit),
           'ACTIVE'
    FROM family_product_key AS product_key
    WHERE NOT EXISTS (
        SELECT 1
        FROM family_key_owner AS owner
        WHERE owner.match_key = product_key.match_key
    )
    GROUP BY product_key.match_key;

    INSERT INTO family_key_owner (match_key, family_id)
    SELECT DISTINCT product_key.match_key, family.id
    FROM family_product_key AS product_key
    JOIN app.product_family AS family
      ON family.family_key = 'MK:' || product_key.match_key
    WHERE NOT EXISTS (
        SELECT 1
        FROM family_key_owner AS owner
        WHERE owner.match_key = product_key.match_key
    );

    ANALYZE family_key_owner;

    UPDATE app.product_family AS family
    SET brand_id = chosen.brand_id,
        quantity_value = chosen.quantity_value,
        base_unit = chosen.base_unit,
        updated_at = NOW()
    FROM (
        SELECT owner.family_id,
               MIN(product_key.brand_id) AS brand_id,
               MIN(product_key.quantity_value) AS quantity_value,
               MIN(product_key.base_unit) AS base_unit
        FROM family_product_key AS product_key
        JOIN family_key_owner AS owner
          ON owner.match_key = product_key.match_key
        GROUP BY owner.family_id
    ) AS chosen
    WHERE chosen.family_id = family.id
      AND (
          family.brand_id IS DISTINCT FROM chosen.brand_id
          OR family.quantity_value IS DISTINCT FROM chosen.quantity_value
          OR family.base_unit IS DISTINCT FROM chosen.base_unit
      );

    UPDATE app.retailer_product AS product
    SET product_family_id = owner.family_id
    FROM family_product_key AS product_key
    JOIN family_key_owner AS owner
      ON owner.match_key = product_key.match_key
    WHERE product.id = product_key.retailer_product_id
      AND product.product_family_id IS DISTINCT FROM owner.family_id;

    INSERT INTO app.product_family_member (
        family_id,
        canonical_product_id,
        relation_type,
        confidence
    )
    SELECT DISTINCT owner.family_id,
           product_key.canonical_product_id,
           'SINGLE_GTIN',
           1.0000
    FROM family_product_key AS product_key
    JOIN family_key_owner AS owner
      ON owner.match_key = product_key.match_key
    WHERE product_key.canonical_product_id IS NOT NULL
    ON CONFLICT (canonical_product_id) DO UPDATE
    SET family_id = EXCLUDED.family_id,
        updated_at = NOW()
    WHERE product_family_member.relation_type <> 'MANUAL'
      AND product_family_member.family_id <> EXCLUDED.family_id;

    -- A list item that pointed at a family which is now empty follows its
    -- products to the family they moved into.
    CREATE TEMP TABLE family_moved_to ON COMMIT DROP AS
    SELECT DISTINCT ON (product_key.current_family_id)
           product_key.current_family_id AS old_family_id,
           owner.family_id AS new_family_id
    FROM family_product_key AS product_key
    JOIN family_key_owner AS owner
      ON owner.match_key = product_key.match_key
    WHERE product_key.current_family_id IS NOT NULL
    GROUP BY product_key.current_family_id, owner.family_id
    ORDER BY product_key.current_family_id, COUNT(*) DESC, owner.family_id;

    UPDATE app.shopping_list_item AS item
    SET matched_product_family_id = moved.new_family_id
    FROM family_moved_to AS moved
    WHERE item.matched_product_family_id = moved.old_family_id
      AND moved.new_family_id <> moved.old_family_id
      AND NOT EXISTS (
          SELECT 1
          FROM app.retailer_product AS product
          WHERE product.product_family_id = moved.old_family_id
      );

    UPDATE app.product_identity_candidate AS candidate
    SET suggested_family_id = moved.new_family_id,
        updated_at = NOW()
    FROM family_moved_to AS moved
    WHERE candidate.suggested_family_id = moved.old_family_id
      AND moved.new_family_id <> moved.old_family_id
      AND NOT EXISTS (
          SELECT 1
          FROM app.retailer_product AS product
          WHERE product.product_family_id = moved.old_family_id
      );

    DELETE FROM app.product_family
    WHERE id IN (
        SELECT id FROM app.product_family
        EXCEPT
        SELECT product_family_id
        FROM app.retailer_product
        WHERE product_family_id IS NOT NULL
        EXCEPT
        SELECT family_id FROM app.product_family_member
        EXCEPT
        SELECT matched_product_family_id
        FROM app.shopping_list_item
        WHERE matched_product_family_id IS NOT NULL
        EXCEPT
        SELECT suggested_family_id FROM app.product_identity_candidate
    );
END;
$$;

SELECT app.assign_product_families(NULL);

-- Presence, type and category describe a family, so they are rebuilt for every
-- chain now instead of waiting for each chain's next price list.
DELETE FROM app.product_retailer_presence;

INSERT INTO app.product_retailer_presence (
    product_family_id,
    retailer_id,
    first_seen_date,
    last_seen_date,
    latest_price_date,
    current_offer_count,
    store_count,
    format_count,
    minimum_effective_price
)
SELECT product.product_family_id,
       product.retailer_id,
       MIN(offer.first_seen_date),
       MAX(offer.last_seen_date),
       MAX(offer.price_date),
       COUNT(*)::INTEGER,
       COUNT(DISTINCT offer.store_id)
           FILTER (WHERE offer.store_id IS NOT NULL)::INTEGER,
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name)))
           FILTER (
               WHERE NULLIF(BTRIM(offer.retailer_format_name), '') IS NOT NULL
           )::INTEGER,
       MIN(
           CASE
               WHEN offer.discounted_price > 0 THEN offer.discounted_price
               WHEN offer.regular_price > 0 THEN offer.regular_price
           END
       )
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
WHERE product.product_family_id IS NOT NULL
GROUP BY product.product_family_id, product.retailer_id;

UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM app.product_family AS target
LEFT JOIN LATERAL (
    SELECT assignment.product_type_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = target.id
    GROUP BY assignment.product_type_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_type_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = target.id
  AND family.product_type_id IS DISTINCT FROM choice.product_type_id;

UPDATE app.product_family AS family
SET product_category_id = choice.product_category_id,
    updated_at = NOW()
FROM app.product_family AS target
JOIN LATERAL (
    SELECT assignment.product_category_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_category AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = target.id
    GROUP BY assignment.product_category_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_category_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = target.id
  AND family.product_category_id IS DISTINCT FROM choice.product_category_id;
