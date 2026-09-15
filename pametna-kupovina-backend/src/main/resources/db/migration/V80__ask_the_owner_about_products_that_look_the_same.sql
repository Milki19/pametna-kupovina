-- Some products are one product under two barcodes and two names, which no
-- rule joins safely. Zaječarsko's jubilee bottle is "PIVO ZAJECAR.JUBIL.0.33
-- ST.NEP." at Europrom and IDEA and "Pivo Zajecarsko 0.33l NRGB" at four other
-- chains. The same brand and size, the words of one name found in the other,
-- the same packaging and no chain that sells both make a pair the owner is
-- asked about. A pair confirmed as one product is one product from the next
-- catalogue rebuild on; a pair rejected is not asked about again.

-- Decisions are kept by family key, which survives a rebuild; family ids do
-- not.
CREATE TABLE app.product_merge_decision (
    left_key VARCHAR(100) NOT NULL,
    right_key VARCHAR(100) NOT NULL,
    into_key VARCHAR(100),
    decision VARCHAR(20) NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (left_key, right_key),
    CONSTRAINT chk_product_merge_decision_order CHECK (left_key < right_key),
    CONSTRAINT chk_product_merge_decision CHECK (
        (decision = 'SAME' AND into_key IN (left_key, right_key))
        OR (decision = 'DIFFERENT' AND into_key IS NULL)
    )
);

CREATE TABLE app.product_merge_suggestion (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    left_family_id BIGINT NOT NULL REFERENCES app.product_family (id) ON DELETE CASCADE,
    right_family_id BIGINT NOT NULL REFERENCES app.product_family (id) ON DELETE CASCADE,
    score NUMERIC(5, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_product_merge_suggestion UNIQUE (left_family_id, right_family_id),
    CONSTRAINT chk_product_merge_suggestion_order CHECK (left_family_id < right_family_id)
);

-- The words that tell one product of a brand from another: no size, brand,
-- packaging or chain stock codes.
CREATE OR REPLACE FUNCTION app.product_distinguishing_words(product_name TEXT, brand_name TEXT)
RETURNS TEXT[]
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(ARRAY_AGG(DISTINCT word ORDER BY word), '{}')
    FROM REGEXP_SPLIT_TO_TABLE(app.fold_match_text(product_name), '[^a-z0-9]+') AS word
    WHERE LENGTH(word) >= 3
      AND word !~ '[0-9]'
      AND word NOT IN (
          'stand', 'var', 'lim', 'limenka', 'limenke', 'can', 'pet', 'pvc',
          'boca', 'flasa', 'staklo', 'stakl', 'nrgb', 'rgb', 'npb', 'nep',
          'jub', 'jubil', 'jubilarna', 'svetlo', 'folija', 'kom', 'kesa', 'the', 'and'
      )
      -- The brand, also cut short: "ZAJECAR." is Zaječarsko.
      AND NOT EXISTS (
          SELECT 1
          FROM REGEXP_SPLIT_TO_TABLE(app.fold_match_text(COALESCE(brand_name, '')), '[^a-z0-9]+')
              AS brand_word
          WHERE brand_word = word
             OR (LENGTH(word) >= 4 AND brand_word LIKE word || '%')
      )
$$;

-- A can, a plastic bottle and a glass bottle of a drink are three products.
CREATE OR REPLACE FUNCTION app.product_package_material(product_name TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
               WHEN folded ~ '(^|[^a-z])(can|lim|limenka|limenke)([^a-z]|$)' THEN 'CAN'
               WHEN folded ~ '(^|[^a-z])(pet|pvc)([^a-z]|$)' THEN 'PLASTIC'
               WHEN folded ~ '(^|[^a-z])(staklo|stakl|rgb|nrgb|npb|pb|nb|nep)([^a-z]|$)' THEN 'GLASS'
           END
    FROM (SELECT app.fold_match_text(product_name) AS folded) AS input
$$;

-- Every word of the shorter name is in the longer one, whole or cut short
-- ("KORNJAČE" and "kornjace", "Micelarn" and "Micelarna"), and the shorter
-- name says at least half as much. One word says too little against two:
-- "pivo" is also "crno pivo".
CREATE OR REPLACE FUNCTION app.names_say_the_same(left_words TEXT[], right_words TEXT[])
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CARDINALITY(smaller) > 0
       AND CARDINALITY(smaller) * 2 >= CARDINALITY(larger)
       AND (CARDINALITY(smaller) >= 2 OR CARDINALITY(larger) = 1)
       AND NOT EXISTS (
           SELECT 1
           FROM UNNEST(smaller) AS word
           WHERE NOT EXISTS (
               SELECT 1
               FROM UNNEST(larger) AS other
               WHERE other LIKE word || '%'
                  OR word LIKE other || '%'
           )
       )
    FROM (
        SELECT CASE WHEN CARDINALITY(left_words) <= CARDINALITY(right_words)
                    THEN left_words ELSE right_words END AS smaller,
               CASE WHEN CARDINALITY(left_words) <= CARDINALITY(right_words)
                    THEN right_words ELSE left_words END AS larger
    ) AS pair
$$;

CREATE OR REPLACE FUNCTION app.refresh_product_merge_suggestions()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DROP TABLE IF EXISTS pg_temp.merge_family;

    CREATE TEMP TABLE merge_family ON COMMIT DROP AS
    SELECT family.id,
           SUBSTRING(family.family_key FROM 4) AS match_key,
           family.normalized_name,
           family.brand_id,
           family.quantity_value,
           family.base_unit,
           family.product_type_id,
           app.product_distinguishing_words(family.display_name, brand.display_name) AS words,
           app.product_package_material(family.display_name) AS material,
           app.family_base_package_count(family.id) AS package_count
    FROM app.product_family AS family
    JOIN app.brand AS brand
      ON brand.id = family.brand_id
    WHERE family.family_key LIKE 'MK:%'
      AND family.quantity_value IS NOT NULL
      AND EXISTS (
          SELECT 1
          FROM app.product_retailer_presence AS presence
          WHERE presence.product_family_id = family.id
      );

    CREATE INDEX ON merge_family (brand_id, quantity_value, base_unit);
    ANALYZE merge_family;

    DELETE FROM app.product_merge_suggestion;

    INSERT INTO app.product_merge_suggestion (left_family_id, right_family_id, score)
    SELECT left_family.id,
           right_family.id,
           ROUND(public.similarity(left_family.normalized_name, right_family.normalized_name)::NUMERIC, 4)
    FROM merge_family AS left_family
    JOIN merge_family AS right_family
      ON right_family.brand_id = left_family.brand_id
     AND right_family.quantity_value = left_family.quantity_value
     AND right_family.base_unit = left_family.base_unit
     AND right_family.package_count = left_family.package_count
     AND right_family.id > left_family.id
     AND right_family.product_type_id IS NOT DISTINCT FROM left_family.product_type_id
    WHERE (
              left_family.material IS NULL
              OR right_family.material IS NULL
              OR left_family.material = right_family.material
          )
      AND app.names_say_the_same(left_family.words, right_family.words)
      -- A chain that sells both sells two products.
      AND NOT EXISTS (
          SELECT 1
          FROM app.product_retailer_presence AS left_presence
          JOIN app.product_retailer_presence AS right_presence
            ON right_presence.retailer_id = left_presence.retailer_id
           AND right_presence.product_family_id = right_family.id
          WHERE left_presence.product_family_id = left_family.id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM app.product_merge_decision AS decision
          WHERE decision.left_key = LEAST(left_family.match_key, right_family.match_key)
            AND decision.right_key = GREATEST(left_family.match_key, right_family.match_key)
      );
END;
$function$;

CREATE OR REPLACE FUNCTION app.assign_product_families(target_retailer_id bigint)
 RETURNS void
 LANGUAGE plpgsql
AS $function$
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
           CASE WHEN weight.loose THEN NULL ELSE source.quantity_value END
               AS quantity_value,
           CASE WHEN weight.loose THEN NULL ELSE source.base_unit END
               AS base_unit,
           app.product_match_key(
               CASE
                   WHEN weight.loose
                       THEN REGEXP_REPLACE(source.name, '\m1\s*(kg|l)\M', ' ', 'gi')
                   ELSE source.name
               END
               -- The size is the whole package, so a case of 24 half-litre
               -- cans and one of 48 quarter-litre cans must still differ.
               || CASE
                      WHEN source.package_count > 1
                          THEN ' ' || source.package_count || 'x'
                      ELSE ''
                  END,
               source.brand_name,
               CASE WHEN weight.loose THEN NULL ELSE source.quantity_value END,
               -- Without a size, a piece and a kilogram are told apart once
               -- the chains' prices confirm it (refresh_sale_units).
               CASE
                   WHEN weight.loose OR source.quantity_value IS NULL
                       THEN source.sale_unit
                   ELSE source.base_unit
               END
           ) AS match_key
    FROM (
        SELECT product.id AS retailer_product_id,
               product.canonical_product_id,
               product.product_family_id AS current_family_id,
               COALESCE(canonical.name, product.name) AS name,
               -- One barcode is one product: chains that write "2X2L" and
               -- "DUO 4L" for it must still give it one key.
               CASE
                   WHEN canonical.id IS NULL THEN product.package_count
                   ELSE (
                       SELECT MAX(same_barcode.package_count)
                       FROM app.retailer_product AS same_barcode
                       WHERE same_barcode.canonical_product_id = canonical.id
                   )
               END AS package_count,
               product.sale_unit,
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
    -- "Celer koren, rinfuz 1kg" is celery sold loose at a price per kilogram,
    -- the same thing other chains list as plain "CELER KOREN". The "1kg" is
    -- the unit of the price, not a package.
    CROSS JOIN LATERAL (
        SELECT source.name ~* '\mrinfuz\M'
               AND source.quantity_value = 1000
               AND source.base_unit IN ('g', 'ml') AS loose
    ) AS weight
    WHERE NULLIF(BTRIM(source.normalized_name), '') IS NOT NULL;

    -- Two products the owner confirmed are one take the key of the one more
    -- chains sell (V80).
    UPDATE family_product_key AS product_key
    SET match_key = decision.into_key
    FROM app.product_merge_decision AS decision
    WHERE decision.decision = 'SAME'
      AND product_key.match_key IN (decision.left_key, decision.right_key)
      AND product_key.match_key <> decision.into_key;

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
$function$;

SELECT app.refresh_product_merge_suggestions();
