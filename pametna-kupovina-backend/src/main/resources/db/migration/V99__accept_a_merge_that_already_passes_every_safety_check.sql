-- V80 asked the owner about every pair that passed brand, size, packaging,
-- product type and word-overlap, because "no rule joins safely". Most of
-- those pairs are the owner's own rule confirmed — same brand and size is
-- the same product — written two ways: reordered, repeated, a packaging
-- code changed. Once brand and size are set aside, the two names leave
-- exactly the same words, and that pair is decided outright instead of
-- asked about, the same way a human's "Isti proizvod" is (same table, same
-- "more chains win" tie-break, ProductReviewService.decideMerge) — so a
-- wrong one is reversible the same way a rejected suggestion always was:
-- flip the decision row.
--
-- A pair left over after that — one side says something the other does
-- not, the way Vidal's "GUMENE" turtles are not the plain ones even though
-- brand, size and most of the name line up — still gets asked about. That
-- asymmetry is exactly what made the owner reject that pair last time;
-- nothing here would have caught it either.
CREATE OR REPLACE FUNCTION app.refresh_product_merge_suggestions()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DROP TABLE IF EXISTS pg_temp.merge_family;
    DROP TABLE IF EXISTS pg_temp.merge_candidate;

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
           app.family_base_package_count(family.id) AS package_count,
           (SELECT COUNT(*) FROM app.product_retailer_presence AS presence
            WHERE presence.product_family_id = family.id) AS chain_count
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

    -- Every pair close enough to ask about, same test as before (V80).
    CREATE TEMP TABLE merge_candidate ON COMMIT DROP AS
    SELECT left_family.id AS left_id,
           right_family.id AS right_id,
           left_family.match_key AS left_key,
           right_family.match_key AS right_key,
           left_family.chain_count AS left_chain_count,
           right_family.chain_count AS right_chain_count,
           left_family.normalized_name AS left_name,
           right_family.normalized_name AS right_name,
           left_family.words = right_family.words AS exact_match
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

    INSERT INTO app.product_merge_decision (left_key, right_key, into_key, decision)
    SELECT LEAST(left_key, right_key),
           GREATEST(left_key, right_key),
           CASE WHEN left_chain_count >= right_chain_count THEN left_key ELSE right_key END,
           'SAME'
    FROM merge_candidate
    WHERE exact_match
    ON CONFLICT (left_key, right_key) DO UPDATE SET
        into_key = EXCLUDED.into_key,
        decision = EXCLUDED.decision,
        decided_at = NOW();

    DELETE FROM app.product_merge_suggestion;

    INSERT INTO app.product_merge_suggestion (left_family_id, right_family_id, score)
    SELECT left_id,
           right_id,
           ROUND(public.similarity(left_name, right_name)::NUMERIC, 4)
    FROM merge_candidate
    WHERE NOT exact_match;
END;
$function$;
