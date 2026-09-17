-- "meso" and "riba" found only fresh meat and fish, and in a shop that sells
-- them only frozen they found nothing. The owner decided (nastavak 29) that
-- they offer frozen meat and fish where a shop has no fresh. "sveže meso" and
-- "sveža riba" still mean fresh.
--
-- Frozen meat and frozen fish are kinds of their own, given where the chain
-- files a product under 7 "Smrznuti proizvodi" and its name says meat or fish:
-- the words of fresh meat and fish without "smrznut", "zamrz" and the frozen
-- brands, and still no breaded, smoked or ready-made food, dough, seafood or
-- fish sticks. On a list they come after fresh meat and fish in the same shop.
--
-- A frozen product named "zamrz", "smrz", "zamr." or "zam." is no longer
-- fresh chicken, neck or meat either: Europrom's "PILECI FILE GRUDI 500g ZAMR
-- CEKI" counted as fresh chicken fillet. Word lists were checked against every
-- chain's products (17.09.).

INSERT INTO app.product_type (code, name, product_category_id, needs_source_category)
SELECT kind.code, kind.name, category.id, TRUE
FROM (
    VALUES
        ('FROZEN_MEAT', 'Smrznuto meso'),
        ('FROZEN_FISH', 'Smrznuta riba')
) AS kind (code, name)
JOIN app.product_category AS category
  ON category.code = 'FROZEN';

-- Frozen words as chains shorten them: "SMRZ.", "SMR", "ZAMRZ.", "ZAMR.", "ZAM.".
UPDATE app.product_type_rule AS rule
SET exclude_pattern = REPLACE(
        rule.exclude_pattern,
        '|smrznut[a-z]*|smrz|smrzn[a-z]*|zamrz[a-z]*|zamr|',
        '|smrznut[a-z]*|smrz|smrzn[a-z]*|smr|zamrz[a-z]*|zamr|zam|'
    ),
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'MEAT'
  AND rule.active
  AND rule.retailer_id IS NULL;

UPDATE app.product_type_rule AS rule
SET exclude_pattern = REPLACE(
        rule.exclude_pattern,
        '|smrznut[a-z]*)\M',
        '|smrznut[a-z]*|smrz[a-z]*|smr|zamrz[a-z]*|zamr|zam)\M'
    ),
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code IN ('CHICKEN_FILLET', 'CHICKEN_DRUMSTICK', 'CHICKEN_WINGS', 'PORK_NECK_FRESH')
  AND rule.active
  AND rule.retailer_id IS NULL;

-- Fresh fish also knows Alaska pollock, pangasius in every case, tilapia,
-- hoki and dogfish, and a fish head with tail and guts for soup is not fish.
UPDATE app.product_type_rule AS rule
SET include_pattern = REPLACE(
        rule.include_pattern,
        '|halibut[a-z]*)\M',
        '|halibut[a-z]*|pang[a-z]*|kolj(a|e|u|om)|aljask[a-z]*|tilapi[a-z]*|hoki|osl|morsk[a-z]* pas)\M'
    ),
    exclude_pattern = REPLACE(
            rule.exclude_pattern,
            '|smrz[a-z]*|zamrz[a-z]*|',
            '|smrz[a-z]*|smr|zamrz[a-z]*|zamr|zam|'
        )
        || '|(?<!bez )\mglav(a|e|u)\M|\m(utrob[a-z]*|unutrasnj[a-z]*|iznutric[a-z]*)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'FISH'
  AND rule.active
  AND rule.retailer_id IS NULL;

-- A tuna steak without oil, brine or a canning brand is not a can.
UPDATE app.product_type_rule AS rule
SET include_pattern = REPLACE(
        rule.include_pattern,
        '^(?!.*\m(dimljen[a-z]*|',
        '^(?!.*\m(steak|stek|snicl[a-z]*|odrez[a-z]*|kotlet[a-z]*|dimljen[a-z]*|'
    ),
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'CANNED_FISH'
  AND rule.active
  AND rule.retailer_id IS NULL
  AND rule.include_pattern LIKE '^(?!%';

-- Frozen meat: the words of fresh meat, without the frozen words, the fish
-- that also sells as fillets and steaks, seafood, and what the frozen aisle
-- adds: burek, mantije, pelmeni, rolls, pancakes, nuggets, fingers,
-- Karađorđeva and Wiener schnitzel, strips, curry medallions and fruit halves.
-- Frozen fish: the words of fresh fish, without the frozen words and brands,
-- breaded fillets ("PAN.", "u panadi"), smoked, with cheese or spinach, sticks
-- ("STAP.") and the sole's namesake, "spanać list".
INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT frozen.id,
       fresh_rule.include_pattern,
       CASE frozen.code
           WHEN 'FROZEN_MEAT' THEN
               REPLACE(
                   fresh_rule.exclude_pattern,
                   '|smrznut[a-z]*|smrz|smrzn[a-z]*|smr|zamrz[a-z]*|zamr|zam|',
                   '|'
               )
               || '|' || fish_rule.include_pattern
               || '|\m(lignj[a-z]*|sip(a|e)|hobotnic[a-z]*|skamp[a-z]*|kozic[a-z]*|gambor[a-z]*|dagnj[a-z]*'
               || '|skolj[a-z]*|kamenic[a-z]*|kapic[a-z]*|rakov[a-z]*|kraba|jastog[a-z]*|plodov[a-z]*|surimi'
               || '|san zak|jakob[a-z]*)\M'
               || '|\m(burek[a-z]*|mantij[a-z]*|pelmen[a-z]*|peljmen[a-z]*|rolnic[a-z]*|rolls|pastrmajlij[a-z]*'
               || '|palacink[a-z]*|palaci[a-z]*|poh|nachos|finger[a-z]*|figer[a-z]*|dinosaur[a-z]*|nagg[a-z]*'
               || '|kuglic[a-z]*|testo|lisnat[a-z]*|becka|becke|karadjordjev[a-z]*|strips[a-z]*|mix|meksick[a-z]*'
               || '|med|kari|mango|mangom|avokad[a-z]*|kajsij[a-z]*)\M'
           ELSE
               REPLACE(
                   REPLACE(
                       fresh_rule.exclude_pattern,
                       '|smrz[a-z]*|smr|zamrz[a-z]*|zamr|zam|',
                       '|'
                   ),
                   '|frikom|frozy|lamargo|iwp|',
                   '|'
               )
               || '|\m(pan|panad[a-z]*|prodimlj[a-z]*|sir|sirom|spanac[a-z]*|spinac[a-z]*|stap)\M'
       END,
       fresh_rule.priority,
       fresh_rule.confidence
FROM app.product_type AS frozen
JOIN app.product_type AS fresh
  ON fresh.code = CASE frozen.code WHEN 'FROZEN_MEAT' THEN 'MEAT' ELSE 'FISH' END
JOIN app.product_type_rule AS fresh_rule
  ON fresh_rule.product_type_id = fresh.id
 AND fresh_rule.active
 AND fresh_rule.retailer_id IS NULL
JOIN app.product_type AS fish
  ON fish.code = 'FISH'
JOIN app.product_type_rule AS fish_rule
  ON fish_rule.product_type_id = fish.id
 AND fish_rule.active
 AND fish_rule.retailer_id IS NULL
WHERE frozen.code IN ('FROZEN_MEAT', 'FROZEN_FISH');

DO $$
BEGIN
    IF (
        SELECT COUNT(*)
        FROM app.product_type_rule AS rule
        JOIN app.product_type AS type
          ON type.id = rule.product_type_id
        WHERE rule.active
          AND rule.retailer_id IS NULL
          AND (
              (type.code = 'MEAT' AND POSITION('|smr|zamrz[a-z]*|zamr|zam|' IN rule.exclude_pattern) > 0)
              OR (type.code IN ('CHICKEN_FILLET', 'CHICKEN_DRUMSTICK', 'CHICKEN_WINGS', 'PORK_NECK_FRESH')
                  AND POSITION('|zamr|zam)\M' IN rule.exclude_pattern) > 0)
              OR (type.code = 'FISH' AND POSITION('|morsk[a-z]* pas)\M' IN rule.include_pattern) > 0
                  AND POSITION('|smr|zamrz[a-z]*|zamr|zam|' IN rule.exclude_pattern) > 0)
              OR (type.code = 'CANNED_FISH' AND POSITION('(steak|stek|' IN rule.include_pattern) > 0)
              OR (type.code = 'FROZEN_MEAT' AND POSITION('zamrz' IN rule.exclude_pattern) = 0)
              OR (type.code = 'FROZEN_FISH' AND POSITION('zamrz' IN rule.exclude_pattern) = 0
                  AND POSITION('frikom' IN rule.exclude_pattern) = 0)
          )
    ) <> 9 THEN
        RAISE EXCEPTION 'V89 did not rewrite the meat and fish rules it expected';
    END IF;
END $$;

-- Between rules of equal priority, the kind the chain's own category agrees
-- with wins: a pollock fillet filed under frozen food is frozen fish, not
-- fresh fish filed in the wrong place, and "FILET SABLJARKE" filed under fish
-- is fish, not meat because of "filet". Before, the older rule won and the
-- product got no kind at all. Otherwise predictions are as in V86.
CREATE OR REPLACE FUNCTION app.predict_product_types(target_retailer_id BIGINT)
RETURNS TABLE (
    retailer_product_id BIGINT,
    product_type_id BIGINT,
    confidence NUMERIC(5, 4),
    prediction_source TEXT,
    evidence TEXT,
    algorithm_version VARCHAR(40)
)
LANGUAGE sql
STABLE
AS $function$
    SELECT product.id,
           matched.product_type_id,
           GREATEST(
               0.0001,
               LEAST(
                   1.0000,
                   matched.rule_confidence
                       + CASE
                             WHEN actual_category.id IS NULL
                               OR expected_category.id IS NULL THEN 0.0000
                             WHEN actual_category.id = expected_category.id
                               OR actual_category.id = expected_category.parent_id
                               OR actual_category.parent_id = expected_category.id
                                 THEN 0.0200
                             ELSE -0.1500
                         END
               )
           )::NUMERIC(5, 4),
           CASE
               WHEN actual_category.id IS NULL THEN 'NAME_RULE'
               WHEN actual_category.id = expected_category.id
                 OR actual_category.id = expected_category.parent_id
                 OR actual_category.parent_id = expected_category.id
                   THEN 'SOURCE_CATEGORY_AND_NAME'
               ELSE 'CONFLICTING_SOURCE_AND_NAME'
           END,
           'RULE:' || matched.rule_id
               || CASE
                      WHEN NULLIF(BTRIM(product.category_code), '') IS NULL
                          THEN ''
                      ELSE ';CATEGORY_CODE:' || BTRIM(product.category_code)
                  END,
           'taxonomy-v14'::VARCHAR(40)
    FROM (
        SELECT DISTINCT ON (match.matched_product_id)
               match.matched_product_id AS retailer_product_id,
               rule.id AS rule_id,
               rule.product_type_id,
               rule.confidence AS rule_confidence
        FROM app.match_product_type_rules(target_retailer_id) AS match
        JOIN app.product_type_rule AS rule
          ON rule.id = match.matched_rule_id
        JOIN app.product_type AS rule_type
          ON rule_type.id = rule.product_type_id
        LEFT JOIN app.product_category AS rule_category
          ON rule_category.id = rule_type.product_category_id
        LEFT JOIN app.retailer_product_category AS filed
          ON filed.retailer_product_id = match.matched_product_id
        LEFT JOIN app.product_category AS filed_category
          ON filed_category.id = filed.product_category_id
        ORDER BY match.matched_product_id,
                 rule.priority,
                 COALESCE(
                     filed_category.id = rule_category.id
                     OR filed_category.id = rule_category.parent_id
                     OR filed_category.parent_id = rule_category.id,
                     FALSE
                 ) DESC,
                 rule.confidence DESC,
                 LENGTH(rule.include_pattern) DESC,
                 rule.id
    ) AS matched
    JOIN app.retailer_product AS product
      ON product.id = matched.retailer_product_id
    JOIN app.product_type AS matched_type
      ON matched_type.id = matched.product_type_id
    LEFT JOIN app.product_category AS expected_category
      ON expected_category.id = matched_type.product_category_id
    LEFT JOIN app.retailer_product_category AS category_assignment
      ON category_assignment.retailer_product_id = product.id
    LEFT JOIN app.product_category AS actual_category
      ON actual_category.id = category_assignment.product_category_id;
$function$;

-- "meso" and "riba" take frozen meat and fish where a shop has no fresh;
-- "sveže meso" and "sveža riba" keep fresh only, as intents of their own.
UPDATE app.shopping_intent
SET name = CASE code WHEN 'MEAT' THEN 'Meso' ELSE 'Riba' END,
    updated_at = NOW()
WHERE code IN ('MEAT', 'FISH');

INSERT INTO app.shopping_intent (code, name)
VALUES
    ('FRESH_MEAT', 'Sveže meso'),
    ('FRESH_FISH', 'Sveža riba');

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT fresh.id,
       allowed.product_type_id,
       allowed.substitution_level,
       allowed.match_priority,
       allowed.enabled_by_default
FROM app.shopping_intent AS fresh
JOIN app.shopping_intent AS generic
  ON generic.code = CASE fresh.code WHEN 'FRESH_MEAT' THEN 'MEAT' ELSE 'FISH' END
JOIN app.shopping_intent_product_type AS allowed
  ON allowed.shopping_intent_id = generic.id
WHERE fresh.code IN ('FRESH_MEAT', 'FRESH_FISH');

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, 'RELATED', 20, TRUE
FROM (
    VALUES
        ('MEAT', 'FROZEN_MEAT'),
        ('FISH', 'FROZEN_FISH')
) AS allowed (intent_code, type_code)
JOIN app.shopping_intent AS intent
  ON intent.code = allowed.intent_code
JOIN app.product_type AS type
  ON type.code = allowed.type_code;

UPDATE app.shopping_intent_alias AS alias
SET shopping_intent_id = intent.id
FROM app.shopping_intent AS intent
WHERE (alias.normalized_alias, intent.code) IN (('sveze meso', 'FRESH_MEAT'), ('sveza riba', 'FRESH_FISH'));

-- "meso" no longer leaves out frozen meat; offal, bones, marinated and small
-- packs stay out.
UPDATE app.shopping_intent_alias
SET required_name_pattern = REPLACE(
        required_name_pattern,
        '|zamr[a-z]*|zamrz[a-z]*|smrz[a-z]*|smrzn[a-z]*|',
        '|'
    )
WHERE normalized_alias = 'meso'
  AND POSITION('|zamr[a-z]*|zamrz[a-z]*|smrz[a-z]*|smrzn[a-z]*|' IN required_name_pattern) > 0;

-- A list written as "sveže meso" or "sveža riba" keeps meaning fresh.
UPDATE app.shopping_list_item AS item
SET shopping_intent_id = alias.shopping_intent_id,
    updated_at = NOW()
FROM app.shopping_intent_alias AS alias
WHERE alias.normalized_alias IN ('sveze meso', 'sveza riba')
  AND item.matching_rule = 'FLEXIBLE_CATEGORY'
  AND item.flexible_category_normalized = alias.normalized_alias
  AND item.shopping_intent_id IS DISTINCT FROM alias.shopping_intent_id;

-- Kinds are made again under the new rules: meat, fish and canned fish, and
-- a frozen product no longer counts as fresh chicken or neck.
DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = assignment.product_type_id
  AND product.id = assignment.retailer_product_id
  AND assignment.reviewed = FALSE
  AND (
      type.code IN ('MEAT', 'FISH', 'CANNED_FISH')
      OR (
          type.code IN ('CHICKEN_FILLET', 'CHICKEN_DRUMSTICK', 'CHICKEN_WINGS', 'PORK_NECK_FRESH')
          AND product.normalized_name ~ '\m(smrz[a-z]*|smr|zamrz[a-z]*|zamr|zam)\M'
      )
  );

SELECT app.assign_generic_product_types(NULL);

DELETE FROM app.product_type_candidate AS candidate
USING app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = candidate.product_type_id
  AND product.id = candidate.retailer_product_id
  AND candidate.status = 'PENDING'
  AND (
      type.code IN ('MEAT', 'FISH', 'CANNED_FISH')
      OR (
          type.code IN ('CHICKEN_FILLET', 'CHICKEN_DRUMSTICK', 'CHICKEN_WINGS', 'PORK_NECK_FRESH')
          AND product.normalized_name ~ '\m(smrz[a-z]*|smr|zamrz[a-z]*|zamr|zam)\M'
      )
  );

INSERT INTO app.product_type_candidate (
    retailer_product_id,
    product_type_id,
    confidence,
    prediction_source,
    evidence,
    algorithm_version
)
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
 AND type.code IN ('MEAT', 'FISH', 'CANNED_FISH', 'FROZEN_MEAT', 'FROZEN_FISH')
JOIN app.retailer_product AS product
  ON product.id = prediction.retailer_product_id
 AND NULLIF(BTRIM(product.category_code), '') IS NULL
WHERE prediction.confidence >= 0.7500
  AND prediction.confidence < 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type AS assignment
      WHERE assignment.retailer_product_id = prediction.retailer_product_id
        AND (assignment.reviewed OR assignment.product_type_id = prediction.product_type_id)
  )
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  )
ON CONFLICT (retailer_product_id, product_type_id, algorithm_version) DO NOTHING;

-- Each affected family shows the kind most of its products have.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    WHERE product.product_family_id IS NOT NULL
      AND (product.category_code IN ('7', '8', '9') OR product.category_code IS NULL)
    UNION
    SELECT family.id
    FROM app.product_family AS family
    JOIN app.product_type AS type
      ON type.id = family.product_type_id
    WHERE type.code IN ('MEAT', 'FISH', 'CANNED_FISH', 'CHICKEN_FILLET', 'CHICKEN_DRUMSTICK', 'CHICKEN_WINGS', 'PORK_NECK_FRESH')
) AS affected
LEFT JOIN LATERAL (
    SELECT assignment.product_type_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = affected.id
    GROUP BY assignment.product_type_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_type_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = affected.id
  AND family.product_type_id IS DISTINCT FROM choice.product_type_id;
