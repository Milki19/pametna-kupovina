-- RIČ's blocks of Paladin gauda (two names, one of them on sale at 899,99),
-- Bajuval, Maasdamer and Tilsiter stood alone, each in a family of its own,
-- so the product page compared them with nothing. RIČ writes the names its
-- own way ("Blok48%3.3kgSilbo000128", "Bajuval-Emental.48%mm3.3kg*Paladin*",
-- "Tilsiter Blok 45%mm 3.3kg BMI 09/23048") and the look-alike rules (V114)
-- never reach them: other chains write "SIR TILSITER 45%MM 3.3KG PALADIN",
-- without "blok" and with "sir".
--
-- A block of hard cheese sold by the kilogram (V127, V128) is one product
-- whatever a chain calls it: the same brand, the same kind of cheese and the
-- same fat in the dry matter. Such families are decided SAME, into the one
-- most chains sell, the way the owner's "Isti proizvod" is (V80), so a
-- wrong one is undone by flipping its decision row. A decision already
-- taken for a pair is kept.
--
-- Slices, grated and smoked cheese, light and aged kinds, flavoured ones and
-- food made with cheese are other products and are left out, as is a family
-- whose names disagree on the brand, kind or fat. The kinds and the words
-- are words of a language, kept here as in V128. The import runs the same
-- rule after every price list, so a block a chain renames joins again.

CREATE OR REPLACE FUNCTION app.join_blocks_of_one_cheese()
RETURNS INTEGER
LANGUAGE plpgsql
AS $function$
DECLARE
    joined INTEGER;
BEGIN
    DROP TABLE IF EXISTS pg_temp.block_product;
    DROP TABLE IF EXISTS pg_temp.block_brand;
    DROP TABLE IF EXISTS pg_temp.block_family;
    DROP TABLE IF EXISTS pg_temp.block_join;

    -- Every product of a one-kilogram family that names a kind of hard cheese.
    CREATE TEMP TABLE block_product ON COMMIT DROP AS
    SELECT family.id AS family_id,
           SUBSTRING(family.family_key FROM 4) AS match_key,
           family.brand_id,
           product.retailer_id,
           folded.words,
           kind.kind,
           (REGEXP_MATCH(product.name, '(?<![0-9])([0-9]{1,2})(?:[.,][0-9]+)?\s*%'))[1] AS fat,
           folded.words ~ ('\m(slajs|slajsovi|slice|secen|secena|seceni|seceno|rezan|rezana'
                           '|rezani|ribani|ribana|rendani|rendana|listici|kocke|kockice'
                           '|stapici|dimljen|dimljena|dimljeni|light|lajt|lagani|lagana'
                           '|laktoze|mini|zreli|zrela|zrelo|odlezali|stari|stara|premium'
                           '|extra|ekstra|pikant|pikantni|ljuti|paprika|paprikom|biber'
                           '|orah|orasi|bilje|biljem|vlasac|tartuf|kozji|ovciji|bivolji'
                           '|pizza|pica|tost|analog|sendvic|rolnice|rolnica|burek|pita'
                           '|pite|kroasan|pohovan|pohovani)\M') AS other_product
    FROM app.product_family AS family
    JOIN app.retailer_product AS product
      ON product.product_family_id = family.id
    CROSS JOIN LATERAL (
        -- RIČ glues a word to the weight before it ("3.3kgPaladin").
        SELECT app.fold_match_text(
                   REGEXP_REPLACE(product.name, '([[:lower:]])([[:upper:]])', '\1 \2', 'g')
               ) AS words
    ) AS folded
    LEFT JOIN LATERAL (
        SELECT reading.kind
        FROM (
            VALUES
                -- Bajuval is Paladin's emmentaler and says so in its name.
                (1, 'bajuval', 'bajuval'),
                (2, 'maasdamer', 'maasdamer|maasdam|masdamer'),
                (3, 'ementaler', 'ementaler|emmentaler|emental|emmental'),
                (4, 'tilsiter', 'tilsiter|tilziter|tilzit'),
                (5, 'trapist', 'trapist|trapis'),
                (6, 'edam', 'edam|edamer|edammer'),
                (7, 'gauda', 'gauda|gouda'),
                (8, 'cheddar', 'cheddar|cedar')
        ) AS reading(priority, kind, pattern)
        WHERE folded.words ~ ('\m(' || reading.pattern || ')\M')
        ORDER BY reading.priority
        LIMIT 1
    ) AS kind ON TRUE
    WHERE family.family_key LIKE 'MK:%'
      AND family.quantity_value = 1000
      AND family.base_unit = 'g'
      AND product.package_count = 1;

    -- The brands of those families, as words found in a name. Some chains
    -- put the maker or nothing in the brand column, so the brand is read
    -- from the name: of the brands it names, the one most blocks carry.
    CREATE TEMP TABLE block_brand ON COMMIT DROP AS
    SELECT brand.id,
           ' ' || app.fold_match_text(brand.display_name) || ' ' AS words,
           COUNT(DISTINCT block.family_id) AS family_count
    FROM block_product AS block
    JOIN app.brand AS brand
      ON brand.id = block.brand_id
    WHERE block.kind IS NOT NULL
      AND LENGTH(app.fold_match_text(brand.display_name)) >= 3
    GROUP BY brand.id, brand.display_name;

    CREATE TEMP TABLE block_family ON COMMIT DROP AS
    SELECT block.family_id,
           block.match_key,
           MIN(named.brand_id) AS brand_id,
           MIN(block.kind) AS kind,
           MIN(block.fat) AS fat,
           COUNT(DISTINCT block.retailer_id) AS chain_count
    FROM block_product AS block
    LEFT JOIN LATERAL (
        SELECT brand.id AS brand_id
        FROM block_brand AS brand
        WHERE ' ' || block.words || ' ' LIKE '%' || brand.words || '%'
        ORDER BY brand.family_count DESC, brand.id
        LIMIT 1
    ) AS named ON TRUE
    GROUP BY block.family_id, block.match_key
    HAVING NOT BOOL_OR(block.other_product)
       AND COUNT(DISTINCT block.kind) = 1
       AND COUNT(DISTINCT named.brand_id) = 1
       AND COUNT(DISTINCT block.fat) <= 1;

    -- A family whose names state no fat belongs to its brand and kind when
    -- they come in one fat only.
    UPDATE block_family AS family
    SET fat = single_fat.fat
    FROM (
        SELECT brand_id, kind, MIN(fat) AS fat
        FROM block_family
        WHERE fat IS NOT NULL
        GROUP BY brand_id, kind
        HAVING COUNT(DISTINCT fat) = 1
    ) AS single_fat
    WHERE family.fat IS NULL
      AND family.brand_id = single_fat.brand_id
      AND family.kind = single_fat.kind;

    -- Each family joins the one of its brand, kind and fat that most chains
    -- sell.
    CREATE TEMP TABLE block_join ON COMMIT DROP AS
    SELECT family.family_id,
           family.match_key AS from_key,
           target.match_key AS into_key
    FROM block_family AS family
    JOIN LATERAL (
        SELECT candidate.match_key
        FROM block_family AS candidate
        WHERE candidate.brand_id = family.brand_id
          AND candidate.kind = family.kind
          AND candidate.fat = family.fat
        ORDER BY candidate.chain_count DESC, candidate.family_id
        LIMIT 1
    ) AS target ON TRUE
    WHERE family.fat IS NOT NULL
      AND target.match_key <> family.match_key
      -- A pair already decided, either way, stays as decided.
      AND NOT EXISTS (
          SELECT 1
          FROM app.product_merge_decision AS decision
          WHERE decision.left_key = LEAST(family.match_key, target.match_key)
            AND decision.right_key = GREATEST(family.match_key, target.match_key)
      )
      -- A name already decided into another family follows that decision.
      AND NOT EXISTS (
          SELECT 1
          FROM app.product_merge_decision AS decision
          WHERE decision.decision = 'SAME'
            AND family.match_key IN (decision.left_key, decision.right_key)
            AND decision.into_key <> family.match_key
      );

    -- Names that joined the family before follow it into the other one.
    INSERT INTO app.product_merge_decision (left_key, right_key, into_key, decision)
    SELECT LEAST(decision.other_key, block.into_key),
           GREATEST(decision.other_key, block.into_key),
           block.into_key,
           'SAME'
    FROM block_join AS block
    JOIN (
        SELECT CASE WHEN left_key = into_key THEN right_key ELSE left_key END AS other_key,
               into_key
        FROM app.product_merge_decision
        WHERE decision = 'SAME'
    ) AS decision
      ON decision.into_key = block.from_key
    WHERE decision.other_key <> block.into_key
    ON CONFLICT (left_key, right_key) DO NOTHING;

    DELETE FROM app.product_merge_decision AS decision
    USING block_join AS block
    WHERE decision.decision = 'SAME'
      AND decision.into_key = block.from_key;

    INSERT INTO app.product_merge_decision (left_key, right_key, into_key, decision)
    SELECT LEAST(from_key, into_key), GREATEST(from_key, into_key), into_key, 'SAME'
    FROM block_join
    ON CONFLICT (left_key, right_key) DO NOTHING;

    GET DIAGNOSTICS joined = ROW_COUNT;
    RETURN joined;
END;
$function$;

SELECT app.join_blocks_of_one_cheese();

-- The decisions take effect as an import applies them (V80).
SELECT app.assign_product_families(NULL);

SELECT app.refresh_family_display_names();

SELECT app.refresh_typical_prices();

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
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name))) FILTER (
           WHERE NULLIF(BTRIM(offer.retailer_format_name), '') IS NOT NULL
       )::INTEGER,
       MIN(app.effective_price(
           offer.regular_price,
           offer.discounted_price,
           offer.discount_start,
           offer.discount_end,
           app.market_today(chain.market_id)
       ))
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
JOIN app.retailer AS chain
  ON chain.id = product.retailer_id
WHERE product.product_family_id IS NOT NULL
  AND app.in_latest_price_list(
      product.retailer_id,
      offer.scope_key,
      offer.price_date,
      app.market_today(chain.market_id)
  )
  AND product.package_count =
      app.family_base_package_count(product.product_family_id)
GROUP BY product.product_family_id, product.retailer_id;

REFRESH MATERIALIZED VIEW app.current_sale_list;
