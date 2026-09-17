-- Every catalogue refresh asks which type each of a chain's products is, and
-- the question got slower with every rule: the prediction view tried all rules
-- on one product before the next. This keeps the rules and their answers and
-- changes how they are asked, so the longer word lists that "meso", "riba",
-- "voće" and "povrće" need (V87, V88) fit in and stay fast.

-- Word lists for fresh meat, fish and produce outgrow VARCHAR(1000), and a
-- btree over whole patterns would outgrow its row size, so the unique index
-- compares hashes. The prediction view reads the columns and is rebuilt below.
DROP VIEW app.product_type_prediction;

ALTER TABLE app.product_type_rule
    ALTER COLUMN include_pattern TYPE TEXT,
    ALTER COLUMN exclude_pattern TYPE TEXT;

DROP INDEX app.uq_product_type_rule_scope_pattern;

CREATE UNIQUE INDEX uq_product_type_rule_scope_pattern
    ON app.product_type_rule (
        COALESCE(retailer_id, 0),
        product_type_id,
        MD5(include_pattern),
        MD5(COALESCE(exclude_pattern, ''))
    );

-- Which products each rule matches, one rule over all products before the
-- next. PostgreSQL keeps 32 compiled patterns, and the view tried every rule
-- on one product after another, so each pattern was compiled again for every
-- product: Lidl's 4,716 products took 14 s, and 61 s with the word lists of
-- V87. One rule at a time they take 0.2 s, with the same predictions for Lidl
-- and Maxi (checked on a copy of the database, 17.09.).
CREATE FUNCTION app.match_product_type_rules(target_retailer_id BIGINT)
RETURNS TABLE (matched_product_id BIGINT, matched_rule_id BIGINT)
LANGUAGE plpgsql
STABLE
AS $function$
DECLARE
    rule RECORD;
BEGIN
    FOR rule IN
        SELECT type_rule.id,
               type_rule.retailer_id,
               type_rule.include_pattern,
               type_rule.exclude_pattern
        FROM app.product_type_rule AS type_rule
        JOIN app.product_type AS type
          ON type.id = type_rule.product_type_id
         AND type.active = TRUE
        WHERE type_rule.active = TRUE
        ORDER BY type_rule.id
    LOOP
        RETURN QUERY
            SELECT product.id, rule.id
            FROM app.retailer_product AS product
            WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
              AND (rule.retailer_id IS NULL OR product.retailer_id = rule.retailer_id)
              AND product.normalized_name ~ rule.include_pattern
              AND (rule.exclude_pattern IS NULL OR product.normalized_name !~ rule.exclude_pattern);
    END LOOP;
END;
$function$;

-- The best rule for each product of one chain, or of all chains, with its
-- confidence: a chain category that agrees raises it, one that disagrees
-- lowers it. Same rules as the view of V46.
CREATE FUNCTION app.predict_product_types(target_retailer_id BIGINT)
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
           'taxonomy-v11'::VARCHAR(40)
    FROM (
        SELECT DISTINCT ON (match.matched_product_id)
               match.matched_product_id AS retailer_product_id,
               rule.id AS rule_id,
               rule.product_type_id,
               rule.confidence AS rule_confidence
        FROM app.match_product_type_rules(target_retailer_id) AS match
        JOIN app.product_type_rule AS rule
          ON rule.id = match.matched_rule_id
        ORDER BY match.matched_product_id,
                 rule.priority,
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

CREATE VIEW app.product_type_prediction AS
SELECT *
FROM app.predict_product_types(NULL);

-- The types a product gets only where its chain files it under the type's
-- category (V83), named on the type instead of listed inside the function.
ALTER TABLE app.product_type
    ADD COLUMN needs_source_category BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE app.product_type
SET needs_source_category = TRUE,
    updated_at = NOW()
WHERE code IN ('SALT', 'FISH', 'MEAT', 'FRESH_PRODUCE');

-- A product whose best rule is of such a type and whose chain files it under
-- that type's category gets the type, unless it has one or the owner removed
-- it. Run for all chains here and for one chain in every catalogue refresh.
CREATE OR REPLACE FUNCTION app.assign_generic_product_types(target_retailer_id BIGINT)
RETURNS INTEGER
LANGUAGE plpgsql
AS $function$
DECLARE
    assigned INTEGER;
BEGIN
    INSERT INTO app.retailer_product_type (
        retailer_product_id,
        product_type_id,
        confidence,
        assignment_source,
        evidence,
        algorithm_version
    )
    SELECT prediction.retailer_product_id,
           prediction.product_type_id,
           0.9500,
           'SOURCE_CATEGORY_AND_NAME',
           prediction.evidence,
           prediction.algorithm_version
    FROM app.predict_product_types(target_retailer_id) AS prediction
    JOIN app.product_type AS type
      ON type.id = prediction.product_type_id
     AND type.needs_source_category = TRUE
    JOIN app.retailer_product_category AS category_assignment
      ON category_assignment.retailer_product_id = prediction.retailer_product_id
     AND category_assignment.assignment_source = 'SOURCE_CATEGORY_CODE'
    WHERE prediction.prediction_source = 'SOURCE_CATEGORY_AND_NAME'
      AND NOT EXISTS (
          SELECT 1
          FROM app.retailer_product_type AS existing
          WHERE existing.retailer_product_id = prediction.retailer_product_id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM app.retailer_product_type_rejection AS rejected
          WHERE rejected.retailer_product_id = prediction.retailer_product_id
            AND rejected.product_type_id = prediction.product_type_id
      )
    ON CONFLICT (retailer_product_id) DO NOTHING;

    GET DIAGNOSTICS assigned = ROW_COUNT;
    RETURN assigned;
END;
$function$;
