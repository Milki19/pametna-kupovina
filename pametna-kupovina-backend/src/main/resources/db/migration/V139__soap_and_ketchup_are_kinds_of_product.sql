-- Izbor proizvoda, četvrti krug (anonimna provera 18.10.2026.):
--
-- * "sapun" i "kecap" nisu bile reči na spisku, pa ih korak 1 nije
--   prepoznao: kupac je za svaku morao da klikne „Neka aplikacija izabere“.
--   Sad su vrste proizvoda, sa svojim rečima.
-- * "Neka aplikacija izabere" je za kečap uzela „K PLUS LJUTI 100G“,
--   najmanju i ljutu flašicu. Za "kecap" je obično blag kečap od oko 500 g;
--   ljut, BBQ i kari dolaze tek kad radnja nema drugi (kao kafa, V125).
-- * Za "sapun" je obično čvrst sapun; tečni dolazi tek kad radnja nema
--   čvrst, a "tečni sapun" je svoja reč, sa svojom veličinom.

-- Nove vrste proizvoda -----------------------------------------------------

-- Kečap lanci svrstavaju u sosove, začine ili prerađeno povrće, a sapun u
-- kozmetiku, bebe ili kućnu hemiju: bez kategorije vrste nijedan od tih izbora
-- ne obara sigurnost ispod praga (-0,15). Proba 19.10.: „Balea čvrsti sapun“,
-- „Camelia sapun med-mleko“ i „Baby sapun Merima“ su ostali bez vrste.
INSERT INTO app.product_type (code, name, product_category_id)
SELECT kind.code, kind.name, category.id
FROM (
    VALUES
        ('SOAP', 'Sapun', NULL),
        ('KETCHUP', 'Kečap', NULL)
) AS kind (code, name, category_code)
LEFT JOIN app.product_category AS category
  ON category.code = kind.category_code
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id, rule.include_pattern, rule.exclude_pattern, rule.priority, 0.9600
FROM (
    VALUES
        (
            'SOAP',
            -- „sapunica“ (mehurići) i „sapunjav“ nisu sapun.
            '\m(sapun|sapuna|sapuni|sapune|soap)\M|\mtec[a-z]* sap\M',
            -- Sapun za veš i za fleke (Merix, Axal, Vanish, marsejski) je deterdžent.
            -- „Čvrsti sapun za tuširanje“ je sapun; gel za tuširanje nije.
            '\m(sampon[a-z]*|shampoo|kupk[a-z]*|gel|brijanj[a-z]*|ves|vesa|rublj[a-z]*'
                || '|deterdzent[a-z]*|det|detergent[a-z]*|merix|axal|vanish|marsej[a-z]*|odmasc[a-z]*|fleka|fleke'
                || '|sudov[a-z]*|sudj[a-z]*|sundjer[a-z]*|rukavic[a-z]*|maramic[a-z]*|kutij[a-z]*|posud[a-z]*|drzac[a-z]*'
                || '|dispenzer[a-z]*|kalup[a-z]*|mehur[a-z]*|balon[a-z]*|igrack[a-z]*|set|poklon[a-z]*'
                || '|kesa|kese|torb[a-z]*|necesser|praznjen[a-z]*)\M'
                -- Dozer ili pumpica bez sapuna: naziv počinje njima.
                || '|^(dozer|dozator|pumpic)[a-z]*\M',
            30
        ),
        (
            'KETCHUP',
            '\m(kecap[a-z]*|ketchup[a-z]*|kecup|ketcup)\M',
            -- Čips i smoki sa ukusom kečapa, pica i sendvič sa kečapom.
            -- „CHIP.REBRASTI KECAP“, „Brrr“ i „Kubeti“ su grickalice (proba 19.10.).
            '\m(cips[a-z]*|chips[a-z]*|chip|brrr|kubet[a-z]*|smoki|flips|bits|grickalic[a-z]*|kokic[a-z]*|stapic[a-z]*|kreker[a-z]*'
                || '|ukus[a-z]*|arom[a-z]*|pica|pizza|sendvic[a-z]*|burger[a-z]*|tortilj[a-z]*|wrap'
                || '|hot dog|hotdog|kobasic[a-z]*|virsl[a-z]*|pomfrit|krompiric[a-z]*|majonez[a-z]*|senf[a-z]*'
                || '|set|poklon[a-z]*)\M',
            30
        )
) AS rule (type_code, include_pattern, exclude_pattern, priority)
JOIN app.product_type AS type
  ON type.code = rule.type_code;

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v18',
        'taxonomy-v19'
    );
END $$;

-- Reči na spisku -----------------------------------------------------------

-- Kečap se meri i u gramima i u mililitrima: veličina bez jedinice.
INSERT INTO app.shopping_intent (
    code,
    name,
    default_min_package_quantity,
    default_max_package_quantity,
    default_base_unit
)
VALUES
    ('SOAP', 'Sapun', 75, 150, 'g'),
    ('LIQUID_SOAP', 'Tečni sapun', 250, 1000, 'ml'),
    ('KETCHUP', 'Kečap', 400, 600, NULL)
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, 'EXACT', 10, TRUE
FROM (
    VALUES
        ('SOAP', 'SOAP'),
        ('LIQUID_SOAP', 'SOAP'),
        ('KETCHUP', 'KETCHUP')
) AS allowed (intent_code, type_code)
JOIN app.shopping_intent AS intent
  ON intent.code = allowed.intent_code
JOIN app.product_type AS type
  ON type.code = allowed.type_code
ON CONFLICT (shopping_intent_id, product_type_id) DO NOTHING;

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern,
    uncommon_name_pattern
)
SELECT intent.id, word.alias, 5, word.required, word.uncommon
FROM (
    VALUES
        ('SOAP', 'sapun', NULL,
            '\m(tecn[a-z]*|tec|liquid|ml|dopun[a-z]*|refill|intim[a-z]*|dec[a-z]*|djec[a-z]*|kids?|baby|beb[a-z]*)\M'),
        ('SOAP', 'sapune', NULL,
            '\m(tecn[a-z]*|tec|liquid|ml|dopun[a-z]*|refill|intim[a-z]*|dec[a-z]*|djec[a-z]*|kids?|baby|beb[a-z]*)\M'),
        ('SOAP', 'cvrst sapun', NULL,
            '\m(tecn[a-z]*|tec|liquid|ml|dopun[a-z]*|refill|intim[a-z]*|dec[a-z]*|djec[a-z]*|kids?|baby|beb[a-z]*)\M'),
        ('SOAP', 'sapun za ruke', NULL,
            '\m(intim[a-z]*|dec[a-z]*|djec[a-z]*|kids?|baby|beb[a-z]*)\M'),
        ('LIQUID_SOAP', 'tecni sapun', '\m(tecn[a-z]*|tec|liquid|ml|dopun[a-z]*|refill)\M',
            '\m(intim[a-z]*|dec[a-z]*|djec[a-z]*|kids?|baby|beb[a-z]*)\M'),
        ('LIQUID_SOAP', 'sapun tecni', '\m(tecn[a-z]*|tec|liquid|ml|dopun[a-z]*|refill)\M',
            '\m(intim[a-z]*|dec[a-z]*|djec[a-z]*|kids?|baby|beb[a-z]*)\M'),
        ('KETCHUP', 'kecap', NULL,
            '\m(ljut[a-z]*|hot|chili|cili|spicy|pikant[a-z]*|bbq|barbecue|curry|kari|light|lajt|bez secera|zero)\M'),
        ('KETCHUP', 'ketchup', NULL,
            '\m(ljut[a-z]*|hot|chili|cili|spicy|pikant[a-z]*|bbq|barbecue|curry|kari|light|lajt|bez secera|zero)\M'),
        ('KETCHUP', 'kecup', NULL,
            '\m(ljut[a-z]*|hot|chili|cili|spicy|pikant[a-z]*|bbq|barbecue|curry|kari|light|lajt|bez secera|zero)\M'),
        ('KETCHUP', 'blagi kecap', NULL,
            '\m(ljut[a-z]*|hot|chili|cili|spicy|pikant[a-z]*|bbq|barbecue|curry|kari|light|lajt|bez secera|zero)\M'),
        ('KETCHUP', 'kecap blagi', NULL,
            '\m(ljut[a-z]*|hot|chili|cili|spicy|pikant[a-z]*|bbq|barbecue|curry|kari|light|lajt|bez secera|zero)\M'),
        ('KETCHUP', 'ljuti kecap', '\m(ljut[a-z]*|hot|chili|cili|spicy|pikant[a-z]*)\M', NULL),
        ('KETCHUP', 'kecap ljuti', '\m(ljut[a-z]*|hot|chili|cili|spicy|pikant[a-z]*)\M', NULL)
) AS word (intent_code, alias, required, uncommon)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
ON CONFLICT (normalized_alias) DO NOTHING;

-- Piletina bez količine ------------------------------------------------------

-- "piletina" bez količine je bila najjeftinije pakovanje: „GRUDI PILEĆE MINI
-- 300G“ i METRO-ov „200G PERUTNINA PILECA V“ (proba 19.10.). Meso se obično
-- kupuje od pola kilograma do kilogram i po; manje pakovanje dolazi posle
-- mesa na meru, a ono posle pakovanja te veličine. Rangira se, ne filtrira.
UPDATE app.shopping_intent
SET default_min_package_quantity = 400,
    default_max_package_quantity = 1500,
    default_base_unit = 'g',
    updated_at = NOW()
WHERE code = 'MEAT'
  AND default_min_package_quantity IS NULL
  AND default_max_package_quantity IS NULL
  AND default_base_unit IS NULL;

-- Vrste po novim pravilima ---------------------------------------------------

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
 AND type.code IN ('SOAP', 'KETCHUP')
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
      AND type.code IN ('SOAP', 'KETCHUP')
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
