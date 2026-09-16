-- Nothing on a list written as "so", "riba", "meso", "voće" or "povrće" found
-- a product. Their types were only ever suggested (rule confidence 0.88-0.92,
-- automatic from 0.95), and about 7,500 suggestions waited for a review
-- nobody did. A product now gets such a type when its name matches and the
-- chain itself files it under that category; a category read from the name
-- alone is not a second opinion. Pet food, spice mixes, soups, snacks,
-- processed fruit and vegetables and fish pâté under meat are left out by
-- name. What still slips through is removed on the admin page, and a
-- removed type does not come back.

CREATE TABLE app.retailer_product_type_rejection (
    retailer_product_id BIGINT NOT NULL REFERENCES app.retailer_product (id) ON DELETE CASCADE,
    product_type_id BIGINT NOT NULL REFERENCES app.product_type (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (retailer_product_id, product_type_id)
);

-- A suggestion already rejected on review stays rejected.
INSERT INTO app.retailer_product_type_rejection (retailer_product_id, product_type_id)
SELECT DISTINCT candidate.retailer_product_id, candidate.product_type_id
FROM app.product_type_candidate AS candidate
WHERE candidate.status = 'REJECTED'
ON CONFLICT DO NOTHING;

UPDATE app.product_type_rule AS rule
SET exclude_pattern = generic.exclude_pattern,
    updated_at = NOW()
FROM app.product_type AS type
JOIN (
    VALUES
        ('SALT', '(^| )(so za kupanje|tabletirana so|galete|galeta|cips|krekeri|kreker|stapici|perece|kikiriki|pistaci|badem|kokice|pranje|kupanje|kupku)( |$)|masin|sudove|regeneraci'),
        ('FISH', '(^| )(za (macke|mace|pse|pasa|ljubimce)|hrana|macke|macji|maca|pse|psi|pseci|psa|sheba|whiskas|felix|friskies|dreamies|kitekat|purina|pedigree|brit|kitty|cat|dog|vitakraft|yums|moksi)( |$)'),
        ('MEAT', '(^| )(za (macke|mace|pse|pasa|ljubimce)|hrana|macke|macji|maca|pse|psi|pseci|psa|sheba|whiskas|felix|friskies|dreamies|kitekat|purina|pedigree|brit|kitty|cat|dog|vitakraft|yums|moksi|posna|posni|vegan|veganska|veganski|biljna|biljni|soja|sojin|zacin|marinada|supa|supe|corba|kocka|kocke|cips|grickalice|riblja|riblji|tuna|tune|losos|sardina|skusa|riba)( |$)'),
        ('FRESH_PRODUCE', '(^| )(sok|dzem|kompot|cips|sirup|nektar|pire|zamrznut|zamrznuta|smrznut|smrznuta|susen|susena|susene|konzerv|kiseli|kisela|ajvar|sos|pasta|pelat|pelati|kasica|chips|flips|komadici|komadi|seckani|seckana|instant|100)( |$)')
) AS generic (type_code, exclude_pattern)
  ON generic.type_code = type.code
WHERE rule.product_type_id = type.id
  AND rule.active;

-- A product whose name matches a generic rule and whose chain files it under
-- that rule's category gets the type, unless a narrower rule claims the name
-- or the owner removed the type. Run for all chains here and for one chain
-- in every catalogue refresh.
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
    SELECT DISTINCT ON (product.id)
           product.id,
           rule.product_type_id,
           0.9500,
           'SOURCE_CATEGORY_AND_NAME',
           'RULE:' || rule.id
               || COALESCE(';CATEGORY_CODE:' || NULLIF(BTRIM(product.category_code), ''), ''),
           'taxonomy-v11'
    FROM app.product_type_rule AS rule
    JOIN app.product_type AS type
      ON type.id = rule.product_type_id
     AND type.active = TRUE
    JOIN app.product_category AS expected
      ON expected.id = type.product_category_id
    JOIN app.retailer_product AS product
      ON product.normalized_name ~ rule.include_pattern
     AND (rule.exclude_pattern IS NULL OR product.normalized_name !~ rule.exclude_pattern)
     AND (rule.retailer_id IS NULL OR rule.retailer_id = product.retailer_id)
    JOIN app.retailer_product_category AS category_assignment
      ON category_assignment.retailer_product_id = product.id
     AND category_assignment.assignment_source = 'SOURCE_CATEGORY_CODE'
    JOIN app.product_category AS actual
      ON actual.id = category_assignment.product_category_id
    WHERE rule.active = TRUE
      AND type.code IN ('SALT', 'FISH', 'MEAT', 'FRESH_PRODUCE')
      AND (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
      AND (
          actual.id = expected.id
          OR actual.id = expected.parent_id
          OR actual.parent_id = expected.id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM app.product_type_rule AS other
          JOIN app.product_type AS other_type
            ON other_type.id = other.product_type_id
           AND other_type.active = TRUE
          WHERE other.active = TRUE
            AND other.id <> rule.id
            AND (other.retailer_id IS NULL OR other.retailer_id = product.retailer_id)
            AND product.normalized_name ~ other.include_pattern
            AND (other.exclude_pattern IS NULL OR product.normalized_name !~ other.exclude_pattern)
            AND (
                other.priority < rule.priority
                OR (other.priority = rule.priority AND other.confidence > rule.confidence)
            )
      )
      AND NOT EXISTS (
          SELECT 1
          FROM app.retailer_product_type AS assigned
          WHERE assigned.retailer_product_id = product.id
      )
      AND NOT EXISTS (
          SELECT 1
          FROM app.retailer_product_type_rejection AS rejected
          WHERE rejected.retailer_product_id = product.id
            AND rejected.product_type_id = rule.product_type_id
      )
    ORDER BY product.id, rule.priority, rule.confidence DESC
    ON CONFLICT (retailer_product_id) DO NOTHING;

    GET DIAGNOSTICS assigned = ROW_COUNT;
    RETURN assigned;
END;
$function$;

SELECT app.assign_generic_product_types(NULL);

-- A suggestion for a type the product now has, or for a name now left out,
-- no longer waits for review.
DELETE FROM app.product_type_candidate AS candidate
USING app.retailer_product_type AS assigned
WHERE candidate.retailer_product_id = assigned.retailer_product_id
  AND candidate.product_type_id = assigned.product_type_id
  AND candidate.status = 'PENDING';

DELETE FROM app.product_type_candidate AS candidate
USING app.product_type_rule AS rule,
      app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = rule.product_type_id
  AND type.code IN ('SALT', 'FISH', 'MEAT', 'FRESH_PRODUCE')
  AND rule.active = TRUE
  AND candidate.product_type_id = rule.product_type_id
  AND candidate.status = 'PENDING'
  AND product.id = candidate.retailer_product_id
  AND product.normalized_name ~ rule.exclude_pattern;

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
