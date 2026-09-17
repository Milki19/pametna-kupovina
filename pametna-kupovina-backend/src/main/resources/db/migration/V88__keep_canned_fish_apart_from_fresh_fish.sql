-- "riba" on a list bought a 95 g tuna pâté, and a can of sardines was the same
-- "riba" as a fresh sea bass. The owner asked where canned fish belongs
-- (nastavak 27). Every source checked on 17.09. keeps it apart from fresh
-- fish: the price lists of Pravilnik 76/2026 file both under 9 "Sveža i
-- prerađena riba", Maxi online under "Riblje konzerve i paštete" beside
-- "Sveža riba i riblji delikates", METRO under "Riblje prerađevine", Cenoteka
-- under "Konzervisani proizvodi" beside "Sveža riba", and IDEA online under
-- "Konzervirano, supe i gotova jela" rather than "Meso i riba".
--
-- So canned fish is a kind of its own, CANNED_FISH, found with "tuna",
-- "tunjevina", "sardine" and "riblja konzerva". "riba" becomes fresh fish, as
-- "meso" became fresh meat in V87: whole fish, fillets and steaks, never
-- canned, smoked, marinated, salted, dried, breaded, pâté, spread or seafood.
-- A can says what it is in its name (oil, brine, sauce, pieces, a salad) or
-- is a small pack of tuna, sardines or mackerel from a canning brand.

UPDATE app.product_type
SET name = 'Sveža riba',
    updated_at = NOW()
WHERE code = 'FISH';

UPDATE app.shopping_intent
SET name = 'Sveža riba',
    updated_at = NOW()
WHERE code = 'FISH';

INSERT INTO app.product_type (code, name, product_category_id, needs_source_category)
SELECT 'CANNED_FISH', 'Riblja konzerva', category.id, TRUE
FROM app.product_category AS category
WHERE category.code = 'FISH';

CREATE TEMP TABLE fish_words (
    list TEXT PRIMARY KEY,
    pattern TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO fish_words (list, pattern)
VALUES
    ('fish',
     '\m(riba|ribe|ribu|losos[a-z]*|pastrmk[a-z]*|brancin[a-z]*|orad[a-z]*|saran[a-z]*|som|soma|smudj[a-z]*'
     || '|stuk[a-z]*|tolstolobik[a-z]*|amur|grgec|keder|deverik[a-z]*|mren[a-z]*|kecig[a-z]*|jesetr[a-z]*'
     || '|oslic[a-z]*|hek|pangasius|pangacius|panga|skus[a-z]*|sardin[a-z]*|srdel[a-z]*|sardel[a-z]*'
     || '|incun[a-z]*|papalin[a-z]*|girice|bakalar[a-z]*|zubatac|zubaca|pagar[a-z]*|kirnj[a-z]*'
     || '|skarpin[a-z]*|romb|list|iverak|grdob[a-z]*|ugor|cipal|lokard[a-z]*|bukv[a-z]*|barbun[a-z]*'
     || '|trlj[a-z]*|sabljark[a-z]*|tun(a|e|u)?|tunj[a-z]*|kokot|arbun|lubin|snapper|salpa|haring[a-z]*'
     || '|sardinel[a-z]*|skusic[a-z]*|halibut[a-z]*)\M'),
    -- Canned, smoked, marinated, salted, dried, breaded or ready-made fish,
    -- pâté, spreads, seafood, fish inside another dish, and packs under
    -- 150 g, which fresh fish is not sold in.
    ('not fresh fish',
     '\m(ulj[a-z]*|salamur[a-z]*|sopstv[a-z]*|sop[a-z]*|sos[a-z]*|umak[a-z]*|pasteta|pastet[a-z]*|past|pate'
     || '|namaz[a-z]*|salat[a-z]*|sal|dimljen[a-z]*|dimnjen[a-z]*|dim|dimlj[a-z]*|marinir[a-z]*|mar'
     || '|susen[a-z]*|suv[a-z]*|usoljen[a-z]*|soljen[a-z]*|slan(a|i|e|o)|panir[a-z]*|pohovan[a-z]*'
     || '|grilovan[a-z]*|stapic[a-z]*|surimi|ikra|kavijar|konzerv[a-z]*|limenk[a-z]*|kopak|komad[a-z]*'
     || '|mrvljen[a-z]*|usitnjen[a-z]*|seckan[a-z]*|povrc[a-z]*|kukuruz[a-z]*|pasulj|leblebij[a-z]*|kus'
     || '|kuskus|couscous|sendvic|burger|pljeskavic[a-z]*|cufte|kroket[a-z]*|smrz[a-z]*|zamrz[a-z]*'
     || '|sushi|carpaccio|tartar|krem|corb[a-z]*|supa|riblj[a-z]*|pica|pizza|lignj[a-z]*|sip(a|e)'
     || '|hobotnic[a-z]*|skamp[a-z]*|kozic[a-z]*|gambor[a-z]*|dagnj[a-z]*|skolj[a-z]*|kamenic[a-z]*'
     || '|kapic[a-z]*|rakov[a-z]*|kraba|jastog[a-z]*|plodov[a-z]*|pesto|paradajz[a-z]*|parad|masline'
     || '|kecap[a-z]*|senf[a-z]*|pikant[a-z]*|limun[a-z]*|papricic[a-z]*|chips|cips|bits|testenin[a-z]*'
     || '|pasta|fusilli|prsut[a-z]*|slice|sliced|slajs|frikom|frozy|lamargo|iwp|bianco|trenton'
     || '|patelin[a-z]*|argeta|carnex|pilec[a-z]*|pilet[a-z]*|pile|curec[a-z]*|svinj[a-z]*|junec[a-z]*'
     || '|telec[a-z]*|jagnjec[a-z]*|meso|mesni'
     || '|ancora nuova|kristal so|kristal|scandia|finissima|old fisherman|zetafish|hellas meze|karagounis'
     || '|labeyrie|ribella)\M'
     || '|\m([1-9][0-9]?|1[0-4][0-9]) (g|gr|grama)\M'),
    -- Brands that sell fish in cans.
    ('canning brand',
     '\m(il capitano|capitano|capit|delamaris|delama|delam|la perla|eva|rio mare|rio mar|giana|calvo'
     || '|compass|malibu|mirna|adria mare|podravka|ducla|kfj|kaiser|trinity|barba|cerio|ortiz|zarotti'
     || '|rizzoli|mareblu|sole di mare|sole di mar|stari beograd|st beograd|st beo|st bgd|star beo'
     || '|franz josef)\M'),
    ('canned fish',
     '\m(tun(a|e|u)?|tunj[a-z]*|sardin[a-z]*|sardel[a-z]*|srdel[a-z]*|skus[a-z]*|skusic[a-z]*|incun[a-z]*'
     || '|haring[a-z]*|papalin[a-z]*|losos[a-z]*|bakalar[a-z]*|riba|ribe|ribu|riblj[a-z]*)\M'),
    -- What a can says it is.
    ('in a can',
     '\m(ulj[a-z]*|salamur[a-z]*|sopstv[a-z]*|sop[a-z]*|sos[a-z]*|umak[a-z]*|konzerv[a-z]*|limenk[a-z]*'
     || '|kopak|komad[a-z]*|mrvljen[a-z]*|usitnjen[a-z]*|usit|seckan[a-z]*|salat[a-z]*|sal|insalat[a-z]*'
     || '|kus|kuskus|couscous|kukuruz[a-z]*|pasulj[a-z]*|grasak|grask[a-z]*|leblebij[a-z]*|povrc[a-z]*'
     || '|povr|pov|meksick[a-z]*|mexican[a-z]*|mexico|texan[a-z]*|italijan[a-z]*|western|balkansk[a-z]*'
     || '|limun[a-z]*|pikant[a-z]*|papricic[a-z]*|paprik[a-z]*|senf[a-z]*|kecap[a-z]*|biber[a-z]*'
     || '|paradajz[a-z]*|parad|morsk[a-z]* vod[a-z]*)\M'),
    -- Tuna, sardines and mackerel in a small pack are canned.
    ('small can',
     '^(?!.*\m(dimljen[a-z]*|dimnjen[a-z]*|dim|dimlj[a-z]*|marinir[a-z]*|svez[a-z]*|ociscen[a-z]*'
     || '|cel(a|i|o)|ceo|na ledu|ziv(a|i|o)|pastrmk[a-z]*|vakum|vakuum|map|rf|cca)\M)'
     || '(?=.*\m(tun(a|e|u)?|tunj[a-z]*|sardin[a-z]*|skus[a-z]*|skusic[a-z]*|incun[a-z]*|haring[a-z]*)\M)'
     || '.*\m([1-9][0-9]?|[12][0-9][0-9]|300) (g|gr|grama)\M'),
    -- Pâté, spreads, smoked slices, salted or dried fish, seafood and
    -- breaded or frozen fish are not canned fish.
    ('not canned fish',
     '\m(pasteta|pastet[a-z]*|past|pate|namaz[a-z]*|krem|panir[a-z]*|pohovan[a-z]*|grilovan[a-z]*'
     || '|stapic[a-z]*|surimi|ikra|kavijar|sushi|sashimi|smrz[a-z]*|zamrz[a-z]*|sendvic|burger|pica|pizza'
     || '|lignj[a-z]*|sip(a|e)|hobotnic[a-z]*|skamp[a-z]*|kozic[a-z]*|gambor[a-z]*|dagnj[a-z]*|skolj[a-z]*'
     || '|kamenic[a-z]*|kapic[a-z]*|rakov[a-z]*|kraba|jastog[a-z]*|plodov[a-z]*|prsut[a-z]*|slice|sliced'
     || '|slajs|frikom|frozy|lamargo|iwp|usoljen[a-z]*|slan(a|i|e|o)|susen[a-z]*|suv(a|i|e|o)'
     || '|patelin[a-z]*|bianco|pasta od|a la|pilec[a-z]*|pilet[a-z]*|pile|curec[a-z]*|svinj[a-z]*'
     || '|junec[a-z]*|telec[a-z]*|jagnjec[a-z]*|meso|mesni)\M'),
    ('pet food',
     '\m(hrana|za (macke|mace|mac|pse|pasa|ljubimce)|macke|macji|maca|mac|pse|psi|pseci|psa|sheba|whiskas'
     || '|whi|felix|friskies|dreamies|kitekat|purina|pedigree|brit|kitty|cat|dog|vitakraft|yums|moksi|cesar'
     || '|gourmet|proof|my love|wise|buddy|zvakalic[a-z]*|poslastic[a-z]*|woof)\M'),
    -- "Gorki list" is a bitter liqueur, not a sole.
    ('not food',
     '\m(l|ml|cl|rakij[a-z]*|liker[a-z]*|koktel[a-z]*|vermut|pelinkovac|gorki list|bitter|alkohol[a-z]*'
     || '|knjg[a-z]*|knjig[a-z]*|karte|noz|igrack[a-z]*|gel|posuda)\M');

UPDATE app.product_type_rule AS rule
SET include_pattern = fish.pattern,
    exclude_pattern = not_fresh.pattern || '|' || brands.pattern || '|' || pets.pattern || '|' || not_food.pattern,
    confidence = 0.8900,
    updated_at = NOW()
FROM app.product_type AS type,
     fish_words AS fish,
     fish_words AS not_fresh,
     fish_words AS brands,
     fish_words AS pets,
     fish_words AS not_food
WHERE type.id = rule.product_type_id
  AND type.code = 'FISH'
  AND rule.active
  AND rule.retailer_id IS NULL
  AND fish.list = 'fish'
  AND not_fresh.list = 'not fresh fish'
  AND brands.list = 'canning brand'
  AND pets.list = 'pet food'
  AND not_food.list = 'not food';

INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id,
       canned.include_pattern,
       not_canned.pattern || '|' || pets.pattern || '|' || not_food.pattern,
       29,
       0.8900
FROM (
    SELECT '^(?=.*' || species.pattern || ').*(' || in_can.pattern || '|' || brands.pattern || ')'
               AS include_pattern
    FROM fish_words AS species,
         fish_words AS in_can,
         fish_words AS brands
    WHERE species.list = 'canned fish'
      AND in_can.list = 'in a can'
      AND brands.list = 'canning brand'
    UNION ALL
    SELECT small.pattern
    FROM fish_words AS small
    WHERE small.list = 'small can'
) AS canned
CROSS JOIN fish_words AS not_canned
CROSS JOIN fish_words AS pets
CROSS JOIN fish_words AS not_food
JOIN app.product_type AS type
  ON type.code = 'CANNED_FISH'
WHERE not_canned.list = 'not canned fish'
  AND pets.list = 'pet food'
  AND not_food.list = 'not food';

-- The rule set changed: predictions and suggestions carry a new version.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v12',
        'taxonomy-v13'
    );
END $$;

-- A can of tuna or sardines is what "tuna" and "sardine" mean; a tuna salad is
-- still found by its whole name. Someone who writes only "tunjevina" means one
-- can, not a pack of four.
INSERT INTO app.shopping_intent (
    code,
    name,
    default_base_unit,
    default_min_package_quantity,
    default_max_package_quantity
)
VALUES ('CANNED_FISH', 'Riblja konzerva', 'g', 80, 200);

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, 'EXACT', 10, TRUE
FROM app.shopping_intent AS intent
JOIN app.product_type AS type USING (code)
WHERE intent.code = 'CANNED_FISH';

WITH plain_tuna (pattern) AS (
    SELECT '^(?!.*\m(salat[a-z]*|sal|insalat[a-z]*|kus|kuskus|couscous|pasulj[a-z]*|grasak|grask[a-z]*'
           || '|leblebij[a-z]*|kukuruz[a-z]*|povrc[a-z]*|povr|pov|meksick[a-z]*|mexican[a-z]*|mexico'
           || '|texan[a-z]*|italijan[a-z]*|western|balkansk[a-z]*|tropican[a-z]*)\M).*\m(tun(a|e|u)?|tunj[a-z]*)\M'
)
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id,
       word.alias,
       5,
       CASE WHEN word.plain_tuna THEN plain_tuna.pattern ELSE word.pattern END
FROM (
    VALUES
        ('FISH', 'sveza riba', FALSE, NULL),
        ('CANNED_FISH', 'riblja konzerva', FALSE, NULL),
        ('CANNED_FISH', 'riblje konzerve', FALSE, NULL),
        ('CANNED_FISH', 'konzerva ribe', FALSE, NULL),
        ('CANNED_FISH', 'tuna', TRUE, NULL),
        ('CANNED_FISH', 'tunjevina', TRUE, NULL),
        ('CANNED_FISH', 'tunjevinu', TRUE, NULL),
        ('CANNED_FISH', 'tuna u konzervi', TRUE, NULL),
        ('CANNED_FISH', 'konzerva tunjevine', TRUE, NULL),
        ('CANNED_FISH', 'sardina', FALSE, '\msardin[a-z]*\M'),
        ('CANNED_FISH', 'sardine', FALSE, '\msardin[a-z]*\M'),
        ('CANNED_FISH', 'sardinu', FALSE, '\msardin[a-z]*\M')
) AS word (intent_code, alias, plain_tuna, pattern)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
CROSS JOIN plain_tuna
ON CONFLICT (normalized_alias) DO NOTHING;

-- Types and suggestions are made again under the new rules.
DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type
WHERE type.id = assignment.product_type_id
  AND type.code = 'FISH'
  AND assignment.reviewed = FALSE;

SELECT app.assign_generic_product_types(NULL);

DELETE FROM app.product_type_candidate AS candidate
USING app.product_type AS type
WHERE type.id = candidate.product_type_id
  AND type.code = 'FISH'
  AND candidate.status = 'PENDING';

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
 AND type.code IN ('FISH', 'CANNED_FISH')
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

UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    WHERE product.product_family_id IS NOT NULL
      AND (product.category_code = '9' OR product.category_code IS NULL)
    UNION
    SELECT family.id
    FROM app.product_family AS family
    JOIN app.product_type AS type
      ON type.id = family.product_type_id
    WHERE type.code = 'FISH'
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
