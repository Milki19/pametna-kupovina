-- Izbor proizvoda, drugi krug (ponovni test 14.10.2026. uveče):
--
-- * "kafa 200 g" je u IDEA-i postala „KAFA CAR 100G ALL CAFFE“. U drugim
--   lancima se isti proizvod zove „proizvod na bazi kafe“; All Caffe nije kafa.
-- * "prašak za veš" je postao „OM DUEL SUNKISSED 1.6L“, omekšivač. „OM“ je
--   omekšivač, a prašak koji se meri u litrima je tečni deterdžent. Lenor,
--   Silan i Ornel ostaju: „Ariel Touch of Lenor“ i „Persil Expert Silan“ su
--   prašak sa mirisom tog omekšivača (proba na kopiji 15.10.).
-- * "toalet papir" je postao jedna rolna („1/1“) ili vlažni papir („VL“).
--   Broj rolni iz „10/1“ čita StoreShoppingOfferRepository.
-- * "sir 500 g" je postao urda, "maslac" margarin.
-- * "piletina 1kg" nije bila reč na spisku, pa je pretraga ponudila hranu za
--   pse. Sad je meso sa piletinom u nazivu, kao i svinjetina, junetina...

-- Kafa ---------------------------------------------------------------------

UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern || '|\mall caff?e\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'COFFEE'
  AND rule.active
  AND rule.retailer_id IS NULL;

-- Prašak za veš -------------------------------------------------------------

UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern || '|\m(om|omek[a-z]*|softener)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'LAUNDRY_DETERGENT'
  AND rule.active
  AND rule.retailer_id IS NULL;

-- Prašak u litrima je tečni deterdžent: dolazi posle praška.
UPDATE app.shopping_intent_alias
SET uncommon_name_pattern = uncommon_name_pattern || '|\m(l|ml|lit|litar[a-z]*|litr[a-z]*)\M'
WHERE normalized_alias IN ('prasak za ves', 'prasak za pranje vesa')
  AND uncommon_name_pattern IS NOT NULL;

-- Toalet papir --------------------------------------------------------------

UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern || '|\m(vl|wet)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'TOILET_PAPER'
  AND rule.active
  AND rule.retailer_id IS NULL;

-- Maslac -------------------------------------------------------------------

-- Margarin, biljni namaz i mešavina nisu maslac, ni kikiriki ili kakao
-- maslac ni krema za telo.
UPDATE app.product_type_rule AS rule
SET exclude_pattern = '\m(margarin[a-z]*|biljn[a-z]*|mesavin[a-z]*|mix|kikiriki[a-z]*|peanut|kakao|cacao'
        || '|badem[a-z]*|lesnik[a-z]*|orah[a-z]*|shea|karite|telo|tela|kozu|losion[a-z]*|balzam[a-z]*'
        || '|usne|keks[a-z]*|kroasan[a-z]*|cokolad[a-z]*|bombon[a-z]*)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'BUTTER'
  AND rule.active
  AND rule.retailer_id IS NULL
  AND rule.exclude_pattern IS NULL;

-- Sir ----------------------------------------------------------------------

-- "sir" je obični beli ili žuti sir; urda, kozji, posni ili plavi sir dolaze
-- tek kad radnja nema drugi.
UPDATE app.shopping_intent_alias
SET uncommon_name_pattern =
        '\m(urd[a-z]*|kozj[a-z]*|ovcij[a-z]*|bivolj[a-z]*|posn[a-z]*|biljn[a-z]*|vegan[a-z]*|tofu'
        || '|dimljen[a-z]*|topljen[a-z]*|namaz[a-z]*|rikot[a-z]*|ricotta|maskarpone|mascarpone'
        || '|halloumi|parmezan[a-z]*|parmigiano|grana|pecorino|gorgonzol[a-z]*|rokfor|roquefort'
        || '|plav[a-z]*|brie|kamamber|camembert|cheddar|cedar|ementaler|emmental[a-z]*|gruyere)\M'
WHERE normalized_alias = 'sir'
  AND uncommon_name_pattern IS NULL;

-- Meso po vrsti --------------------------------------------------------------

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, word.alias, 5, word.pattern
FROM (
    VALUES
        ('piletina', '\m(pile|pilec[a-z]*|pilet[a-z]*|pilen[a-z]*|pil|kokosk[a-z]*|kokosj[a-z]*|roster)\M'),
        ('piletinu', '\m(pile|pilec[a-z]*|pilet[a-z]*|pilen[a-z]*|pil|kokosk[a-z]*|kokosj[a-z]*|roster)\M'),
        ('pilece meso', '\m(pile|pilec[a-z]*|pilet[a-z]*|pilen[a-z]*|pil|kokosk[a-z]*|kokosj[a-z]*|roster)\M'),
        ('svinjetina', '\msvinj[a-z]*\M'),
        ('svinjetinu', '\msvinj[a-z]*\M'),
        ('svinjsko meso', '\msvinj[a-z]*\M'),
        ('junetina', '\m(junec[a-z]*|junetin[a-z]*|jun)\M'),
        ('junetinu', '\m(junec[a-z]*|junetin[a-z]*|jun)\M'),
        ('junece meso', '\m(junec[a-z]*|junetin[a-z]*|jun)\M'),
        ('teletina', '\m(telec[a-z]*|teletin[a-z]*)\M'),
        ('govedina', '\m(goved[a-z]*|govedin[a-z]*)\M'),
        ('curetina', '\m(curec[a-z]*|curetin[a-z]*|curk[a-z]*)\M'),
        ('jagnjetina', '\m(jagnje|jagnjec[a-z]*|jagnjetin[a-z]*)\M')
) AS word (alias, pattern)
JOIN app.shopping_intent AS intent
  ON intent.code = 'MEAT'
ON CONFLICT (normalized_alias) DO NOTHING;

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v15',
        'taxonomy-v16'
    );
END $$;

-- Vrste po novim pravilima ---------------------------------------------------

-- Mašinske vrste se računaju ponovo; ručno potvrđene ostaju.
DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type
WHERE type.id = assignment.product_type_id
  AND type.code IN ('COFFEE', 'LAUNDRY_DETERGENT', 'TOILET_PAPER', 'BUTTER')
  AND assignment.reviewed = FALSE;

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
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
 AND type.code IN ('COFFEE', 'LAUNDRY_DETERGENT', 'TOILET_PAPER', 'BUTTER')
WHERE prediction.confidence >= 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  )
ON CONFLICT (retailer_product_id) DO NOTHING;

-- Svaka porodica proizvoda u ovim vrstama pokazuje vrstu koju ima većina.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    JOIN app.product_type AS type
      ON type.id = assignment.product_type_id
    WHERE product.product_family_id IS NOT NULL
      AND type.code IN ('COFFEE', 'LAUNDRY_DETERGENT', 'TOILET_PAPER', 'BUTTER')
    UNION
    SELECT family.id
    FROM app.product_family AS family
    JOIN app.product_type AS type
      ON type.id = family.product_type_id
    WHERE type.code IN ('COFFEE', 'LAUNDRY_DETERGENT', 'TOILET_PAPER', 'BUTTER')
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
