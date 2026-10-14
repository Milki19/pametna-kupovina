-- Izbor proizvoda iz testiranja 14.10.2026. (stavke 1-8 i 16-18):
--
-- * "kafa 200 g" je u Maxiju postala 13 x „Ice Coffee Freeze Grand 16g“, a u
--   IDEA-i „PROIZVOD NA BAZI KAFE 200G“. Ledena kafa je instant kafa, a
--   proizvod na bazi kafe nije kafa.
-- * "šećer 500 g" je postao šećer u prahu, "banane 1,5 kg" u Novom Sadu crvene
--   banane od 660 RSD/kg. Reč na spisku znači uobičajenu vrstu; neobična vrsta
--   (u prahu, crvena, u zrnu) dolazi tek kad radnja nema uobičajenu. To piše uz
--   reč na spisku (shopping_intent_alias), na jeziku te reči, pa svako tržište
--   ima svoje.
-- * "prasak za ves", "sampon za kosu", "toalet papir" i "coca cola 2l" nisu
--   bile vrste proizvoda. Sad jesu, sa svojim rečima.
-- * "BRAŠNO T-400 1 kg": tip brašna je reč na spisku, kao vrsta sira (V120).
--
-- Rangiranje (uobičajena vrsta, kazna za mnogo malih pakovanja, rinfuz na kg
-- kao deljiv) je u StoreShoppingOfferRepository.

ALTER TABLE app.shopping_intent_alias
    ADD COLUMN uncommon_name_pattern TEXT;

COMMENT ON COLUMN app.shopping_intent_alias.uncommon_name_pattern IS
    'Proizvodi čiji normalizovan naziv odgovara su neobična vrsta za ovu reč: '
    'biraju se tek kad radnja nema drugu ponudu za stavku (V125).';

-- Kafa ---------------------------------------------------------------------

-- Ledena kafa je instant kafa ("Ice Coffee Freeze", "ledena kafa").
UPDATE app.product_type_rule AS rule
SET include_pattern = rule.include_pattern
        || '|\m(ice|iced|ledena|ledenu|freeze)\M.*\m(kafa|kafe|coffee)\M'
        || '|\m(kafa|kafe|coffee)\M.*\m(ice|iced|ledena|ledenu|freeze)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'INSTANT_COFFEE'
  AND rule.active
  AND rule.retailer_id IS NULL;

-- Kafa nije ledena kafa ni proizvod na bazi kafe, zamena ili liker.
UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern
        || '|\m(ice|iced|ledena|ledenu|freeze|na bazi|zamen[a-z]*|surogat[a-z]*'
        || '|cikorij[a-z]*|jecm[a-z]*|liker[a-z]*)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'COFFEE'
  AND rule.active
  AND rule.retailer_id IS NULL;

-- Šećer --------------------------------------------------------------------

-- Vanilin šećer, želirni šećer i zaslađivači nisu šećer.
UPDATE app.product_type_rule AS rule
SET exclude_pattern = '\m(vanil[a-z]*|zelir[a-z]*|zasladjivac[a-z]*|stevi[a-z]*'
        || '|dekor[a-z]*|posip[a-z]*|mrvic[a-z]*|sirup[a-z]*|vuna|bombon[a-z]*'
        || '|zvak[a-z]*|guma)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'SUGAR'
  AND rule.active
  AND rule.retailer_id IS NULL
  AND rule.exclude_pattern IS NULL;

-- Nove vrste proizvoda -----------------------------------------------------

INSERT INTO app.product_type (code, name, product_category_id)
SELECT kind.code, kind.name, category.id
FROM (
    VALUES
        ('LAUNDRY_DETERGENT', 'Deterdžent za veš', 'HOUSEHOLD_CLEANING'),
        ('SHAMPOO', 'Šampon', 'PERSONAL_CARE'),
        ('TOILET_PAPER', 'Toalet papir', 'PAPER_KITCHEN'),
        ('SOFT_DRINK', 'Gazirani sok', 'BEVERAGES')
) AS kind (code, name, category_code)
LEFT JOIN app.product_category AS category
  ON category.code = kind.category_code
ON CONFLICT (code) DO NOTHING;

-- Prodavnica koja proizvod svrsta drugde (-0,15) ostavlja ga na pregled.
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
            'LAUNDRY_DETERGENT',
            '\m(deterdzent[a-z]*|prasak|gel|tecni|kapsul[a-z]*|tablet[a-z]*)\M.*\m(ves|vesa|pranje)\M'
                || '|\m(ves|vesa)\M.*\m(deterdzent[a-z]*|prasak|gel|kapsul[a-z]*)\M'
                || '|\m(ariel|persil|merix|bonux|tide|perwoll|dixan|formil|duel|savex)\M',
            '\m(omeksivac[a-z]*|sudov[a-z]*|posud[a-z]*|wc|toalet[a-z]*|kupatil[a-z]*|stakl[a-z]*'
                || '|podov[a-z]*|tepih[a-z]*|odstranjivac[a-z]*|fleka|fleke|izbeljivac[a-z]*|perl[a-z]*'
                || '|kamenac|calgon|pecivo|pecilni|kos[ae]|tus)\M',
            30
        ),
        (
            'SHAMPOO',
            '\m(sampon[a-z]*|shampoo[a-z]*)\M',
            '\m(tepih[a-z]*|auto|automobil[a-z]*|pse|pas|psa|pasa|macke|macak|ljubim[a-z]*|pet|cetk[a-z]*)\M',
            30
        ),
        (
            'TOILET_PAPER',
            '\m(toalet|toaletni|wc) papir[a-z]*\M|\mpapir[a-z]* (toalet[a-z]*|wc)\M',
            '\m(vlazn[a-z]*|maramic[a-z]*|drzac[a-z]*|cetk[a-z]*|osvezivac[a-z]*|sredstv[a-z]*|daska|gel|blok)\M',
            30
        ),
        (
            'SOFT_DRINK',
            '\m(coca cola|cola|pepsi|fanta|sprite|cockta|mirinda|7 up)\M',
            '\m(bombon[a-z]*|zvak[a-z]*|guma|keks[a-z]*|cips|sirup[a-z]*|liker[a-z]*|rum|jack|daniel[a-z]*'
                || '|whisk[a-z]*|viski|vodk[a-z]*|bacardi|gin|cokolad[a-z]*|sladoled[a-z]*|kafa|caj|zele'
                || '|case|casa|torb[a-z]*|kapa|majic[a-z]*|igrack[a-z]*)\M',
            15
        )
) AS rule (type_code, include_pattern, exclude_pattern, priority)
JOIN app.product_type AS type
  ON type.code = rule.type_code;

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v14',
        'taxonomy-v15'
    );
END $$;

-- Reči na spisku -----------------------------------------------------------

INSERT INTO app.shopping_intent (
    code,
    name,
    default_min_package_quantity,
    default_max_package_quantity,
    default_base_unit
)
VALUES
    -- Prašak u kilogramima i gel u litrima: bilo koja jedinica, 1-6,5 kg ili l.
    ('LAUNDRY_DETERGENT', 'Deterdžent za veš', 1000, 6500, NULL),
    ('SHAMPOO', 'Šampon', 200, 500, 'ml'),
    -- Pakovanje od 8 do 24 rolne.
    ('TOILET_PAPER', 'Toalet papir', 8, 24, NULL),
    ('SOFT_DRINK', 'Gazirani sok', 1500, 2000, 'ml')
ON CONFLICT (code) DO NOTHING;

-- Gazirani sok koji je pravilo za sok već svrstalo i dalje odgovara, posle
-- onog svrstanog kao gazirani sok.
INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, allowed.level, allowed.priority, TRUE
FROM (
    VALUES
        ('LAUNDRY_DETERGENT', 'LAUNDRY_DETERGENT', 'EXACT', 10),
        ('SHAMPOO', 'SHAMPOO', 'EXACT', 10),
        ('TOILET_PAPER', 'TOILET_PAPER', 'EXACT', 10),
        ('SOFT_DRINK', 'SOFT_DRINK', 'EXACT', 10),
        ('SOFT_DRINK', 'JUICE', 'RELATED', 20)
) AS allowed (intent_code, type_code, level, priority)
JOIN app.shopping_intent AS intent
  ON intent.code = allowed.intent_code
JOIN app.product_type AS type
  ON type.code = allowed.type_code
ON CONFLICT (shopping_intent_id, product_type_id) DO NOTHING;

-- "coca cola 2l" je dva litra Coca-Cole, kako god je radnja pakuje; ne
-- Pepsi ni Coca-Cola Zero (ko nju hoće, napiše je). Bez marke ("gazirani sok") važi bilo koji.
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, word.alias, 5, word.pattern
FROM (
    VALUES
        ('LAUNDRY_DETERGENT', 'prasak za ves', NULL),
        ('LAUNDRY_DETERGENT', 'prasak za pranje vesa', NULL),
        ('LAUNDRY_DETERGENT', 'deterdzent za ves', NULL),
        ('LAUNDRY_DETERGENT', 'deterdzent za pranje vesa', NULL),
        ('LAUNDRY_DETERGENT', 'gel za ves', NULL),
        ('LAUNDRY_DETERGENT', 'tecni deterdzent', NULL),
        ('SHAMPOO', 'sampon', NULL),
        ('SHAMPOO', 'sampon za kosu', NULL),
        ('SHAMPOO', 'shampoo', NULL),
        ('TOILET_PAPER', 'toalet papir', NULL),
        ('TOILET_PAPER', 'toaletni papir', NULL),
        ('TOILET_PAPER', 'wc papir', NULL),
        ('SOFT_DRINK', 'gazirani sok', NULL),
        ('SOFT_DRINK', 'gazirano pice', NULL),
        ('SOFT_DRINK', 'coca cola', '^(?!.*\m(zero|light|bez secera)\M).*\mcoca\M'),
        ('SOFT_DRINK', 'koka kola', '^(?!.*\m(zero|light|bez secera)\M).*\mcoca\M'),
        ('SOFT_DRINK', 'pepsi', '^(?!.*\m(max|zero|light)\M).*\mpepsi\M'),
        ('SOFT_DRINK', 'fanta', '\mfanta\M'),
        ('SOFT_DRINK', 'sprite', '\msprite\M'),
        ('SOFT_DRINK', 'cockta', '\mcockta\M')
) AS word (intent_code, alias, pattern)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
ON CONFLICT (normalized_alias) DO NOTHING;

-- Tip brašna: "brašno T-400" je brašno tog tipa.
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, alias.phrase, 5, '\m(t|tip) ?' || kind.number || '\M'
FROM (VALUES ('400'), ('500'), ('550'), ('850'), ('1100')) AS kind (number)
CROSS JOIN LATERAL (
    VALUES
        ('brasno t ' || kind.number),
        ('brasno tip ' || kind.number),
        ('t ' || kind.number || ' brasno'),
        ('tip ' || kind.number || ' brasno')
) AS alias (phrase)
JOIN app.shopping_intent AS intent
  ON intent.code = 'FLOUR'
ON CONFLICT (normalized_alias) DO NOTHING;

-- Šećer koji neko izričito traži: u prahu, smeđi, u kockama.
INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, word.alias, 5, word.pattern
FROM (
    VALUES
        ('secer u prahu', '\mprah[a-z]*\M'),
        ('smedji secer', '\m(smedj[a-z]*|trsk[a-z]*|demerara|muscovado)\M'),
        ('secer smedji', '\m(smedj[a-z]*|trsk[a-z]*|demerara|muscovado)\M'),
        ('secer u kockama', '\mkock[a-z]*\M'),
        ('secer kocke', '\mkock[a-z]*\M')
) AS word (alias, pattern)
JOIN app.shopping_intent AS intent
  ON intent.code = 'SUGAR'
ON CONFLICT (normalized_alias) DO NOTHING;

-- Uobičajena vrsta: šta reč na spisku podrazumeva, a šta je neobično.
UPDATE app.shopping_intent_alias AS alias
SET uncommon_name_pattern = word.pattern
FROM (
    VALUES
        ('kafa', '\m(zrn[a-z]*|zrno|beans?|kapsul[a-z]*|caps|pods?|jastucic[a-z]*|decaf|kofein[a-z]*)\M'),
        ('mlevena kafa', '\m(kapsul[a-z]*|caps|pods?|jastucic[a-z]*|decaf|kofein[a-z]*)\M'),
        ('kafa mlevena', '\m(kapsul[a-z]*|caps|pods?|jastucic[a-z]*|decaf|kofein[a-z]*)\M'),
        ('domaca kafa', '\m(zrn[a-z]*|zrno|beans?|kapsul[a-z]*|caps|pods?|decaf|kofein[a-z]*)\M'),
        ('secer', '\m(prah[a-z]*|smedj[a-z]*|braon|trsk[a-z]*|kock[a-z]*|demerara|muscovado|kandis|kokos[a-z]*|bio|organsk[a-z]*)\M'),
        ('prasak za ves', '\m(kapsul[a-z]*|caps|gel|tecn[a-z]*|tablet[a-z]*|maramic[a-z]*)\M'),
        ('prasak za pranje vesa', '\m(kapsul[a-z]*|caps|gel|tecn[a-z]*|tablet[a-z]*|maramic[a-z]*)\M'),
        ('banana', '\m(crven[a-z]*|baby|mini|bio|organsk[a-z]*|platan[a-z]*|plantain)\M'),
        ('banane', '\m(crven[a-z]*|baby|mini|bio|organsk[a-z]*|platan[a-z]*|plantain)\M'),
        ('bananu', '\m(crven[a-z]*|baby|mini|bio|organsk[a-z]*|platan[a-z]*|plantain)\M'),
        ('jaja', '\m(prepelic[a-z]*|guscij[a-z]*|pacij[a-z]*|bio|organsk[a-z]*)\M'),
        ('jaje', '\m(prepelic[a-z]*|guscij[a-z]*|pacij[a-z]*|bio|organsk[a-z]*)\M'),
        ('mleko', '\m(kozj[a-z]*|ovcij[a-z]*|laktoz[a-z]*|lactose|bio|organsk[a-z]*|protein[a-z]*)\M'),
        ('mlijeko', '\m(kozj[a-z]*|ovcij[a-z]*|laktoz[a-z]*|lactose|bio|organsk[a-z]*|protein[a-z]*)\M'),
        ('obicno mleko', '\m(kozj[a-z]*|ovcij[a-z]*|laktoz[a-z]*|lactose|bio|organsk[a-z]*|protein[a-z]*)\M'),
        ('brasno', '\m(integral[a-z]*|heljd[a-z]*|kukuruz[a-z]*|pirinc[a-z]*|ovs[a-z]*|razen[a-z]*|raz|spelt[a-z]*'
            || '|pir|gluten[a-z]*|kokos[a-z]*|badem[a-z]*|leblebij[a-z]*|00|griz)\M'),
        ('pirinac', '\m(basmati|jasmin[a-z]*|integral[a-z]*|divlj[a-z]*|crn[a-z]*|crven[a-z]*|arborio|risotto|sushi)\M')
) AS word (alias, pattern)
WHERE alias.normalized_alias = word.alias;

-- Vrste po novim pravilima ---------------------------------------------------

-- Mašinske vrste kafe i šećera se računaju ponovo; ručno potvrđene ostaju.
DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type
WHERE type.id = assignment.product_type_id
  AND type.code IN ('COFFEE', 'INSTANT_COFFEE', 'SUGAR')
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
 AND type.code IN (
     'COFFEE', 'INSTANT_COFFEE', 'SUGAR',
     'LAUNDRY_DETERGENT', 'SHAMPOO', 'TOILET_PAPER', 'SOFT_DRINK'
 )
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
      AND type.code IN (
          'COFFEE', 'INSTANT_COFFEE', 'SUGAR',
          'LAUNDRY_DETERGENT', 'SHAMPOO', 'TOILET_PAPER', 'SOFT_DRINK'
      )
    UNION
    SELECT family.id
    FROM app.product_family AS family
    JOIN app.product_type AS type
      ON type.id = family.product_type_id
    WHERE type.code IN ('COFFEE', 'INSTANT_COFFEE', 'SUGAR')
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
