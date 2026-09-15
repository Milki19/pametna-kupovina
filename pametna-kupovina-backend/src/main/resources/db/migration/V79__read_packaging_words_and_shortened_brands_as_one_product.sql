-- One drink under several names. Chains abbreviate the same one-way bottle as
-- NB, NPB, NRGB or ST.NEP. and the same can as CAN, LIM or LIMENKA, so
-- Zaječarsko 0,33 l in a one-way bottle was two products and the can in 0,5 l
-- was split from METRO's. Plain beer is light, so "svetlo" adds nothing.
-- Univerexport cuts brands to eight letters ("ZAJECARS", "PALMOLIV"), which
-- kept its products apart from every other chain's.

CREATE OR REPLACE FUNCTION app.product_match_key(product_name text, brand_name text, quantity_value numeric, base_unit text)
 RETURNS text
 LANGUAGE sql
 IMMUTABLE PARALLEL SAFE
AS $function$
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
               STRING_TO_ARRAY(name_without_quantity, ' ') AS all_words,
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
                ('cokoladne', 'cokolad'),
                -- How a drink is packed, as each chain abbreviates it (V79):
                -- a one-way bottle, a returnable bottle, a can, a plastic one.
                ('nb', 'nepovratna'), ('npb', 'nepovratna'),
                ('nrgb', 'nepovratna'), ('nep', 'nepovratna'),
                ('nepovr', 'nepovratna'), ('nepovratno', 'nepovratna'),
                ('pb', 'povratna'), ('rgb', 'povratna'),
                ('stak', 'povratna'), ('staklo', 'povratna'),
                ('povratno', 'povratna'),
                ('can', 'limenka'), ('lim', 'limenka'), ('limenke', 'limenka'),
                ('pvc', 'pet'),
                -- Words that say nothing about which product it is.
                ('boca', ''), ('flasa', ''), ('flasica', ''),
                ('jub', ''), ('jubil', ''), ('jubilarna', ''),
                ('heineken', ''), ('heinek', ''), ('hein', ''),
                ('carlsberg', ''), ('carlsb', '')
        ) AS synonym(word, replacement)
          ON synonym.word = raw.value
        CROSS JOIN LATERAL UNNEST(
            STRING_TO_ARRAY(COALESCE(synonym.replacement, raw.value), ' ')
        ) AS word(value)
        WHERE word.value <> ''
          -- Beer is light unless it says otherwise: "svetlo" adds nothing.
          AND NOT (
              word.value IN ('svetlo', 'svetl', 'svet')
              AND 'pivo' = ANY (effective.all_words)
          )
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
$function$;;

-- A brand of eight letters or fewer, used only by Univerexport, that is the
-- beginning of exactly one brand other chains use, is that brand. The folded
-- key compares them, as "ZAJEČARSKO" and "Zajecarsko" are one brand.
CREATE OR REPLACE FUNCTION app.remap_truncated_brands()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DROP TABLE IF EXISTS pg_temp.truncated_brand_key;
    DROP TABLE IF EXISTS pg_temp.truncated_brand_remap;

    CREATE TEMP TABLE truncated_brand_key ON COMMIT DROP AS
    SELECT brand.id,
           brand.normalized_name,
           brand.display_name,
           app.product_match_brand_key(brand.display_name) AS brand_key,
           BOOL_AND(retailer.code = 'UNIVEREXPORT') AS only_univerexport
    FROM app.brand AS brand
    JOIN app.retailer_product AS product ON product.brand_id = brand.id
    JOIN app.retailer AS retailer ON retailer.id = product.retailer_id
    GROUP BY brand.id, brand.normalized_name, brand.display_name;

    CREATE TEMP TABLE truncated_brand_remap ON COMMIT DROP AS
    SELECT short.id AS short_id,
           short.normalized_name AS short_name,
           MIN(longer.id) AS long_id
    FROM truncated_brand_key AS short
    JOIN truncated_brand_key AS longer
      ON longer.brand_key LIKE short.brand_key || '%'
     AND LENGTH(longer.brand_key) > LENGTH(short.brand_key)
     AND NOT longer.only_univerexport
    WHERE short.only_univerexport
      AND short.brand_key <> ''
      AND LENGTH(REGEXP_REPLACE(short.display_name, '[^[:alnum:]]', '', 'g')) BETWEEN 6 AND 8
    GROUP BY short.id, short.normalized_name
    HAVING COUNT(DISTINCT longer.brand_key) = 1;

    INSERT INTO app.brand_alias (brand_id, normalized_alias)
    SELECT remap.long_id, remap.short_name
    FROM truncated_brand_remap AS remap
    ON CONFLICT (normalized_alias) DO UPDATE SET brand_id = EXCLUDED.brand_id;

    UPDATE app.retailer_product AS product
    SET brand_id = remap.long_id
    FROM truncated_brand_remap AS remap
    WHERE product.brand_id = remap.short_id;

    UPDATE app.canonical_product AS canonical
    SET brand_id = remap.long_id
    FROM truncated_brand_remap AS remap
    WHERE canonical.brand_id = remap.short_id;

    UPDATE app.product_family AS family
    SET brand_id = remap.long_id
    FROM truncated_brand_remap AS remap
    WHERE family.brand_id = remap.short_id;
END;
$function$;

SELECT app.remap_truncated_brands();

SELECT app.assign_product_families(NULL);

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

SELECT app.refresh_price_list_snapshots();

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
       COUNT(DISTINCT offer.store_id) FILTER (WHERE offer.store_id IS NOT NULL)::INTEGER,
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name))) FILTER (
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
  AND app.in_latest_price_list(product.retailer_id, offer.scope_key, offer.price_date, CURRENT_DATE)
  AND product.package_count = app.family_base_package_count(product.product_family_id)
GROUP BY product.product_family_id, product.retailer_id;

SELECT app.refresh_typical_prices();
