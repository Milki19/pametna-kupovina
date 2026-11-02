-- Vrsta ostaje u odeljenju koje kaže cenovnik (revizija vrsta, 2.11.2026.):
--
-- Svaki red cenovnika nosi službenu šifru kategorije (KATEGORIJA, 1-25), i
-- ima je 99,5% proizvoda sa cenom. Do sada se vrsta birala samo po nazivu, a
-- kategorija proizvoda je i sama dolazila iz naziva kad šifra nije bila u
-- tabeli (01-09, 24, 25), pa se naziv potvrđivao sam sobom. Odatle greške
-- koje su se krpile listama izuzetaka: „Dove Men Energy“ kao energetsko piće,
-- „HIPP GRAND“ (Vim) kao hrana za bebe, „Dreamies sir“ kao sir, vino „Felix
-- Solis“ kao hrana za mačke.
--
-- Opšte pravilo: šifra kategorije pripada jednom odeljenju (hrana i piće,
-- alkohol, hrana za bebe, kućna hemija i kozmetika, ljubimci), a i svaka
-- vrsta. Pravilo vrste iz drugog odeljenja ne važi za proizvod. Na kopiji
-- baze to uklanja 627 pogrešnih vrsta, a pravo pravilo tada dolazi na red
-- (šampon „3u1“, sirće „Vino Župa“, pivo koje vozi Coca-Cola).
--
-- Kad je odeljenje samo jedna vrsta, odeljenje je dovoljno: šifra 22 je
-- hrana za bebe (+1059 proizvoda bez vrste), a u šifri 25 reč za mačku ili
-- psa bira vrstu (+371 i +473), bez nabrajanja skraćenica i brendova lanaca.
-- Drugo tržište upisuje svoje šifre u app.source_category_domain.

-- Odeljenja ---------------------------------------------------------------

CREATE TABLE app.source_category_domain (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id BIGINT REFERENCES app.retailer (id),
    -- Bez vodećih nula i razmaka: "07" i "7" su ista šifra.
    source_category_code VARCHAR(100) NOT NULL,
    domain VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_source_category_domain
        UNIQUE NULLS NOT DISTINCT (retailer_id, source_category_code),
    CONSTRAINT chk_source_category_domain_code_not_blank
        CHECK (BTRIM(source_category_code) <> ''),
    CONSTRAINT chk_source_category_domain_domain
        CHECK (domain IN ('FOOD', 'ALCOHOL', 'BABY', 'HOUSEHOLD', 'PET'))
);

-- Ključ šifre kakav je u tabeli odeljenja. PARALLEL SAFE: pretraga pravila
-- ostaje paralelna, kao pre.
CREATE FUNCTION app.source_category_key(code TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $function$
    SELECT NULLIF(LTRIM(UPPER(BTRIM(code)), '0'), '');
$function$;

-- Službene kategorije cenovnika u Srbiji.
INSERT INTO app.source_category_domain (retailer_id, source_category_code, domain)
VALUES
    (NULL, '1', 'FOOD'),       -- Mleko, mlečni i mešoviti proizvodi, jaja
    (NULL, '2', 'FOOD'),       -- Bezalkoholna pića, kafa, čaj
    (NULL, '3', 'FOOD'),       -- Sveže voće i povrće
    (NULL, '4', 'FOOD'),       -- Prerada voća i povrća i biljni namazi
    (NULL, '5', 'FOOD'),       -- Hleb i peciva
    (NULL, '6', 'FOOD'),       -- Mahunarke
    (NULL, '7', 'FOOD'),       -- Smrznuti proizvodi
    (NULL, '8', 'FOOD'),       -- Sveže i prerađeno meso
    (NULL, '9', 'FOOD'),       -- Sveža i prerađena riba
    (NULL, '10', 'FOOD'),      -- Slani konditori
    (NULL, '11', 'FOOD'),      -- Slatki konditori i cerealije
    (NULL, '12', 'FOOD'),      -- Šećer i med
    (NULL, '13', 'FOOD'),      -- Brašno, dodaci za pečenje i kolače
    (NULL, '14', 'FOOD'),      -- Testenine i kore
    (NULL, '15', 'FOOD'),      -- Ulja i masti
    (NULL, '16', 'FOOD'),      -- Sirće
    (NULL, '17', 'FOOD'),      -- Pirinač i druge žitarice
    (NULL, '18', 'FOOD'),      -- Supe, so, začini i prilozi jelima
    (NULL, '19', 'HOUSEHOLD'), -- Kućna hemija
    (NULL, '20', 'HOUSEHOLD'), -- Papirna i kuhinjska galanterija
    (NULL, '21', 'HOUSEHOLD'), -- Lična higijena i kozmetika
    (NULL, '22', 'BABY'),      -- Hrana za bebe
    (NULL, '23', 'HOUSEHOLD'), -- Pelene
    (NULL, '24', 'ALCOHOL'),   -- Alkoholna pića
    (NULL, '25', 'PET');       -- Hrana za kućne ljubimce

-- Lanci tečni sapun stavljaju u kućnu hemiju, a ubruse u kozmetiku: unutar
-- odeljenja kategorija ne odlučuje, kao i do sada.
ALTER TABLE app.product_type
    ADD COLUMN domain VARCHAR(20),
    ADD CONSTRAINT chk_product_type_domain
        CHECK (domain IS NULL OR domain IN ('FOOD', 'ALCOHOL', 'BABY', 'HOUSEHOLD', 'PET'));

UPDATE app.product_type
SET domain = CASE
        WHEN code IN ('LAUNDRY_DETERGENT', 'DISH_DETERGENT', 'SHAMPOO', 'TOOTHPASTE', 'SOAP',
                      'TOILET_PAPER', 'PAPER_TOWEL') THEN 'HOUSEHOLD'
        WHEN code IN ('BEER', 'FLAVORED_BEER', 'WHEAT_BEER', 'DARK_BEER', 'NON_ALCOHOLIC_BEER',
                      'WINE', 'GIN') THEN 'ALCOHOL'
        WHEN code = 'BABY_FOOD' THEN 'BABY'
        WHEN code IN ('CAT_FOOD', 'DOG_FOOD') THEN 'PET'
        ELSE 'FOOD'
    END,
    updated_at = NOW();

-- Pravilo koje važi samo u odeljenju svoje vrste.
ALTER TABLE app.product_type_rule
    ADD COLUMN within_domain BOOLEAN NOT NULL DEFAULT FALSE;

-- Pravila po odeljenju --------------------------------------------------------

CREATE OR REPLACE FUNCTION app.match_product_type_rules(target_retailer_id BIGINT)
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
               type_rule.exclude_pattern,
               type_rule.within_domain,
               type.domain,
               ARRAY(
                   SELECT department.source_category_code
                   FROM app.source_category_domain AS department
                   WHERE department.domain = type.domain
               ) AS domain_codes
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
            LEFT JOIN app.source_category_domain AS own
              ON own.retailer_id = product.retailer_id
             AND own.source_category_code = app.source_category_key(product.category_code)
            LEFT JOIN app.source_category_domain AS common
              ON common.retailer_id IS NULL
             AND common.source_category_code = app.source_category_key(product.category_code)
            WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
              AND (rule.retailer_id IS NULL OR product.retailer_id = rule.retailer_id)
              -- Pravilo odeljenja gleda samo šifre tog odeljenja.
              AND (NOT rule.within_domain OR app.source_category_key(product.category_code) = ANY (rule.domain_codes))
              -- Vrsta iz drugog odeljenja ne važi; bez šifre odlučuje naziv.
              AND (
                  rule.domain IS NULL
                  OR COALESCE(own.domain, common.domain) IS NULL
                  OR COALESCE(own.domain, common.domain) = rule.domain
              )
              AND (NOT rule.within_domain OR COALESCE(own.domain, common.domain) = rule.domain)
              AND product.normalized_name ~ rule.include_pattern
              AND (rule.exclude_pattern IS NULL OR product.normalized_name !~ rule.exclude_pattern);
    END LOOP;
END;
$function$;

INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence,
    within_domain
)
SELECT type.id, rule.include_pattern, rule.exclude_pattern, 4, 0.9700, TRUE
FROM (
    VALUES
        (
            -- Šifra 22 je samo hrana za bebe: „JUVITANA KAS.128GR“, „Cokolino“,
            -- mleko za odojčad. Mleko, čaj i sok su retki (reči na spisku, V144).
            'BABY_FOOD',
            '.',
            NULL
        ),
        (
            -- Reč za mačku, ili brend za mačke kad nema reči za psa.
            'CAT_FOOD',
            '^(?!.*\m(pse|psa|psi|pas|pasa|pseci|psece|pasj[a-z]*|dog|dogs|puppy|kuce|kucad|kucic[a-z]*|rase)\M)'
                || '.*\m(mack[a-z]*|mace|macic[a-z]*|macj[a-z]*|macij[a-z]*|mac|cat|cats|kitten[a-z]*|kitty)\M'
                || '|^(?!.*\m(pse|psa|psi|pas|pasa|pseci|psece|pasj[a-z]*|dog|dogs|puppy|kuce|kucad|kucic[a-z]*|rase'
                || '|pedigree|pedigre|pedig|ped|chappi|cesar|cane|denta|dentastix|stix|biscrok|markies|rodeo'
                || '|darling|eurodog|dogero|dogi|buddy|fincsi|chunx|quicker|picnic|frolic|mr woof)\M)'
                || '.*\m(whiskas|wiskas|wihskas|whis|whi|felix|sheba|kitekat|gatto|gourmet|gourment|sterilcat'
                || '|eurocat|cattos|lechat|perfect fit|dreamies|miamor|catessy|cat chow|wise|wisecat|pure delight)\M',
            'PET_ACCESSORY'
        ),
        (
            -- Reč za psa, ili brend za pse kad nema reči za mačku.
            'DOG_FOOD',
            '^(?!.*\m(mack[a-z]*|mace|macic[a-z]*|macj[a-z]*|macij[a-z]*|mac|cat|cats|kitten[a-z]*|kitty)\M)'
                || '.*\m(pse|psa|psi|pas|pasa|pseci|psece|pasj[a-z]*|dog|dogs|puppy|kuce|kucad|kucic[a-z]*|rase)\M'
                || '|^(?!.*\m(mack[a-z]*|mace|macic[a-z]*|macj[a-z]*|macij[a-z]*|mac|cat|cats|kitten[a-z]*|kitty'
                || '|whiskas|wiskas|wihskas|whis|whi|felix|sheba|kitekat|gatto|gourmet|gourment|sterilcat'
                || '|eurocat|cattos|lechat|perfect fit|dreamies|miamor|catessy|cat chow|wise|wisecat|pure delight)\M)'
                || '.*\m(pedigree|pedigre|pedig|ped|chappi|cesar|cane|denta|dentastix|stix|biscrok|markies|rodeo'
                || '|darling|eurodog|dogero|dogi|buddy|fincsi|chunx|quicker|picnic|frolic|mr woof)\M',
            'PET_ACCESSORY'
        )
) AS rule (type_code, include_pattern, exclude_pattern)
JOIN app.product_type AS type
  ON type.code = rule.type_code
CROSS JOIN LATERAL (
    SELECT CASE
               WHEN rule.exclude_pattern = 'PET_ACCESSORY' THEN
                   -- Oprema, posip i hrana za druge životinje iz istog odeljenja.
                   '\m(posud[a-z]* (za|inox)|cinij[a-z]*|sofa|krevet[a-z]*|lezalj[a-z]*|jastuk[a-z]*|pesak|peska'
                       || '|posip[a-z]*|igrack[a-z]*|ogrlic[a-z]*|povod[a-z]*|am|maramic[a-z]*|sampon[a-z]*'
                       || '|cetk[a-z]*|cesalj[a-z]*|transporter[a-z]*|kavez[a-z]*|toalet[a-z]*|grebalic[a-z]*'
                       || '|collar|harness|bed|bowl|jacket|boots|toy|ball|lopt[a-z]*|pelen[a-z]*|kese za'
                       || '|sprej[a-z]*|kapi|ampul[a-z]*|ptic[a-z]*|papag[a-z]*|nimf[a-z]*|tigric[a-z]*'
                       || '|kanarinc[a-z]*|hrck[a-z]*|zamor[a-z]*|ribic[a-z]*|kornjac[a-z]*|glodar[a-z]*|svinje'
                       || '|za svinj[a-z]*|prasad|prasic[a-z]*|koke|kokos[a-z]* nosilj[a-z]*|brojler[a-z]*'
                       || '|stocn[a-z]*|krav[a-z]*|tov[a-z]*|grower|piglet|layer)\M'
               ELSE rule.exclude_pattern
           END AS exclude_pattern
) AS exclusion;

-- Izuzetak „kesa“ (plastične kese) je izbacivao kesice hrane („KITEKAT EX
-- LOSOS KESA 85G“); kese iz drugih odeljenja sad odbija odeljenje.
UPDATE app.product_type_rule AS rule
SET exclude_pattern = REPLACE(rule.exclude_pattern, '|kesa|kese)\M', ')\M'),
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code IN ('CAT_FOOD', 'DOG_FOOD')
  AND rule.within_domain = FALSE
  AND POSITION('|kesa|kese)\M' IN rule.exclude_pattern) > 0;

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v20',
        'taxonomy-v21'
    );
END $$;

-- Vrste po odeljenjima ---------------------------------------------------------

CREATE TEMPORARY TABLE product_department ON COMMIT DROP AS
SELECT product.id AS retailer_product_id,
       product.product_family_id,
       COALESCE(own.domain, common.domain) AS domain
FROM app.retailer_product AS product
LEFT JOIN app.source_category_domain AS own
  ON own.retailer_id = product.retailer_id
 AND own.source_category_code = app.source_category_key(product.category_code)
LEFT JOIN app.source_category_domain AS common
  ON common.retailer_id IS NULL
 AND common.source_category_code = app.source_category_key(product.category_code)
WHERE COALESCE(own.domain, common.domain) IS NOT NULL;

CREATE TEMPORARY TABLE department_kind ON COMMIT DROP AS
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       type.code AS type_code,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
WHERE prediction.confidence >= 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  );

CREATE TEMPORARY TABLE changed_product (retailer_product_id BIGINT PRIMARY KEY) ON COMMIT DROP;

-- Mašinska vrsta iz drugog odeljenja se briše; ručno potvrđena ostaje.
WITH removed AS (
    DELETE FROM app.retailer_product_type AS assignment
    USING app.product_type AS type,
          product_department AS department
    WHERE type.id = assignment.product_type_id
      AND department.retailer_product_id = assignment.retailer_product_id
      AND type.domain IS NOT NULL
      AND type.domain <> department.domain
      AND assignment.reviewed = FALSE
    RETURNING assignment.retailer_product_id
)
INSERT INTO changed_product
SELECT DISTINCT retailer_product_id FROM removed
ON CONFLICT DO NOTHING;

-- Pravilo odeljenja je jače od naziva: „hrana za kuce junior friskies“ je pas.
WITH replaced AS (
    DELETE FROM app.retailer_product_type AS assignment
    USING department_kind AS prediction
    WHERE assignment.retailer_product_id = prediction.retailer_product_id
      AND assignment.product_type_id <> prediction.product_type_id
      AND prediction.type_code IN ('BABY_FOOD', 'CAT_FOOD', 'DOG_FOOD')
      AND assignment.reviewed = FALSE
    RETURNING assignment.retailer_product_id
)
INSERT INTO changed_product
SELECT DISTINCT retailer_product_id FROM replaced
ON CONFLICT DO NOTHING;

WITH added AS (
    INSERT INTO app.retailer_product_type (
        retailer_product_id,
        product_type_id,
        confidence,
        assignment_source,
        evidence,
        algorithm_version
    )
    SELECT retailer_product_id,
           product_type_id,
           confidence,
           prediction_source,
           evidence,
           algorithm_version
    FROM department_kind
    ON CONFLICT (retailer_product_id) DO NOTHING
    RETURNING retailer_product_id
)
INSERT INTO changed_product
SELECT retailer_product_id FROM added
ON CONFLICT DO NOTHING;

SELECT app.assign_generic_product_types(NULL);

-- Predlog vrste iz drugog odeljenja ne čeka pregled.
DELETE FROM app.product_type_candidate AS candidate
USING app.product_type AS type,
      product_department AS department
WHERE type.id = candidate.product_type_id
  AND department.retailer_product_id = candidate.retailer_product_id
  AND type.domain IS NOT NULL
  AND type.domain <> department.domain
  AND candidate.status = 'PENDING';

-- Svaka porodica sa promenjenim proizvodom pokazuje vrstu koju ima većina.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    JOIN changed_product AS changed
      ON changed.retailer_product_id = product.id
    WHERE product.product_family_id IS NOT NULL
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
