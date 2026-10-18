-- Izbor proizvoda i cene, iz ponovnog testa 17.10.2026. (web 2.0.15):
--
-- * "paradajz" (1 kom) je u Lidlu postao „Paradajz miks 500g“ po 399,98 RSD/kg,
--   a Lidl ima rinfuz paradajz po 189,99 RSD/kg. Rinfuz se merio (V90, V125)
--   samo kad spisak kaže koliko grama treba. Voće i povrće bez količine je
--   sad kilogram na meru, pre najmanjeg pakovanja. Koja reč se kupuje na meru
--   piše uz reč na spisku (shopping_intent), pa svako tržište ima svoje.
-- * Pretraga "prasak za ves" je prvo nudila „PERSIL korpa za pr veš“: brend
--   Persil je bio dovoljan za deterdžent. Proizvod koji počinje korpom,
--   mrežicom ili štipaljkama je ta stvar, a ne deterdžent. Deterdžent uz koji
--   ide korpa gratis („PERSIL POWDER 5,4+KORPA ZA VES“) ostaje deterdžent
--   (proba 18.10.: takvih je 40, samih korpi 3).
-- * API je za RIČ Prokuplje davao unitPrice 1.569,99 (redovna cena po kg) uz
--   effectivePrice 899,99. Lanac jediničnu cenu daje za redovnu cenu, pa je
--   app.effective_unit_price prevodi na cenu koja danas važi.

-- Voće i povrće na meru --------------------------------------------------------

ALTER TABLE app.shopping_intent
    ADD COLUMN weighed_out BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN app.shopping_intent.weighed_out IS
    'Kupuje se na meru: bez količine na spisku znači kilogram rinfuza, '
    'koji ide pre upakovanog proizvoda (V137).';

UPDATE app.shopping_intent
SET weighed_out = TRUE,
    updated_at = NOW()
WHERE code IN ('FRUIT', 'VEGETABLE', 'FRESH_PRODUCE');

-- Prašak za veš -------------------------------------------------------------

UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern
        || '|^(korp|kosar|mrez|stipalj|susilic|susac)[a-z]*\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'LAUNDRY_DETERGENT'
  AND rule.active
  AND rule.retailer_id IS NULL
  AND rule.exclude_pattern IS NOT NULL
  AND POSITION('^(korp|kosar' IN rule.exclude_pattern) = 0;

DELETE FROM app.product_type_candidate AS candidate
USING app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = candidate.product_type_id
  AND type.code = 'LAUNDRY_DETERGENT'
  AND product.id = candidate.retailer_product_id
  AND product.normalized_name ~ '^(korp|kosar|mrez|stipalj|susilic|susac)[a-z]*\M'
  AND candidate.status = 'PENDING';

-- Mašinska vrsta se skida; ručno potvrđena ostaje.
CREATE TEMPORARY TABLE untyped_laundry_family ON COMMIT DROP AS
SELECT DISTINCT product.product_family_id AS id
FROM app.retailer_product_type AS assignment
JOIN app.product_type AS type
  ON type.id = assignment.product_type_id
JOIN app.retailer_product AS product
  ON product.id = assignment.retailer_product_id
WHERE type.code = 'LAUNDRY_DETERGENT'
  AND assignment.reviewed = FALSE
  AND product.product_family_id IS NOT NULL
  AND product.normalized_name ~ '^(korp|kosar|mrez|stipalj|susilic|susac)[a-z]*\M';

DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = assignment.product_type_id
  AND type.code = 'LAUNDRY_DETERGENT'
  AND product.id = assignment.retailer_product_id
  AND assignment.reviewed = FALSE
  AND product.normalized_name ~ '^(korp|kosar|mrez|stipalj|susilic|susac)[a-z]*\M';

-- Porodica pokazuje vrstu koju ima većina njenih proizvoda, ili nijednu.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM untyped_laundry_family AS affected
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

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v17',
        'taxonomy-v18'
    );
END $$;

-- Jedinična cena koja danas važi ------------------------------------------------

-- Lanac daje jediničnu cenu (po kg, l ili komadu) za jednu od svojih cena,
-- skoro uvek za redovnu. Ista srazmera važi i za cenu koja danas važi: na
-- akciji je jedinična cena niža isto koliko i cena pakovanja. Kad lanac nije
-- napisao za koju cenu je dao jediničnu, odlučuje veličina pakovanja.
CREATE FUNCTION app.effective_unit_price(
    unit_price NUMERIC,
    regular_price NUMERIC,
    discounted_price NUMERIC,
    effective_price NUMERIC,
    quantity_value NUMERIC,
    base_unit TEXT
)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
               -- Neki lanci (RIČ) pišu 0: to nije cena.
               WHEN unit_price IS NULL OR unit_price <= 0 OR effective_price IS NULL
                   THEN NULLIF(unit_price, 0)
               ELSE ROUND(unit_price * effective_price / NULLIF(basis.price, 0), 2)
           END
    FROM (
        SELECT CASE
                   WHEN unit_price = discounted_price THEN discounted_price
                   WHEN unit_price = regular_price THEN regular_price
                   WHEN regular_price > 0
                    AND discounted_price > 0
                    AND quantity_value > 0
                       THEN CASE
                                WHEN ABS(unit_price * quantity_value
                                         / CASE WHEN base_unit IN ('g', 'ml') THEN 1000 ELSE 1 END
                                         - discounted_price)
                                   < ABS(unit_price * quantity_value
                                         / CASE WHEN base_unit IN ('g', 'ml') THEN 1000 ELSE 1 END
                                         - regular_price)
                                    THEN discounted_price
                                ELSE regular_price
                            END
                   ELSE COALESCE(NULLIF(regular_price, 0), discounted_price)
               END AS price
    ) AS basis
$$;
