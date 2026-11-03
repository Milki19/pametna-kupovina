-- Brend koji pravi hranu i za mačke i za pse ne bira životinju (posle V153):
--
-- V144 je Friskies stavio među brendove samo za mačke, a kod nas Friskies
-- prodaje i granule za pse (Active, Balance, Junior 500 g i 2,4 kg) i
-- poslastice za pse (TBonz, Beggin' Strips). Lanci koji ne napišu „za pse“
-- („FRISKIES GRANULE ACTIVE 0.5KG“, „FRISKIES TBONZ 150G“) dobijali su
-- hranu za mačke. Od sada samo reč za mačku ili psa (V153) bira vrstu.
--
-- Životinju tada kaže isti barkod kod drugih lanaca: u odeljenju ljubimaca
-- su samo dve vrste, pa kad bar tri lanca za isti barkod kažu jednu
-- životinju, i to bar triput češće od svih ostalih (računajući i ovaj
-- proizvod), proizvod dobija tu vrstu. Na kopiji baze to ispravlja 8
-- proizvoda: šest Friskies za pse i dva Cesar za pse koje je jedan lanac
-- nazvao „hrana za mačke“; kesice „FRISKIES GOVEDINA 85G“ ostaju mačke po
-- barkodu. Ručno potvrđena i odbijena vrsta se ne diraju. Odeljenje je iz
-- app.source_category_domain, pa važi i za druga tržišta bez spiska brendova.
-- Osvežavanje kataloga poziva istu funkciju posle pravila po nazivu, jer
-- ono mašinske vrste računa iznova.

UPDATE app.product_type_rule AS rule
SET include_pattern = REPLACE(rule.include_pattern, '|friskies|', '|'),
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'CAT_FOOD'
  AND POSITION('|friskies|' IN rule.include_pattern) > 0;

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v21',
        'taxonomy-v22'
    );
END $$;

-- Vrsta po barkodu u odeljenju ljubimaca ---------------------------------------

CREATE FUNCTION app.assign_pet_kinds_by_barcode(target_retailer_id BIGINT)
RETURNS TABLE (changed_product_id BIGINT)
LANGUAGE plpgsql
AS $function$
BEGIN
    RETURN QUERY
    WITH pet_product AS (
        SELECT product.id,
               product.barcode,
               assignment.product_type_id AS current_type_id
        FROM app.retailer_product AS product
        LEFT JOIN app.retailer_product_type AS assignment
          ON assignment.retailer_product_id = product.id
        LEFT JOIN app.product_type AS current_type
          ON current_type.id = assignment.product_type_id
        LEFT JOIN app.source_category_domain AS own
          ON own.retailer_id = product.retailer_id
         AND own.source_category_code = app.source_category_key(product.category_code)
        LEFT JOIN app.source_category_domain AS common
          ON common.retailer_id IS NULL
         AND common.source_category_code = app.source_category_key(product.category_code)
        WHERE (target_retailer_id IS NULL OR product.retailer_id = target_retailer_id)
          AND product.barcode ~ '^[0-9]{8,14}$'
          AND COALESCE(assignment.reviewed, FALSE) = FALSE
          -- Odeljenje ljubimaca po šifri, ili po vrsti kad šifre nema.
          AND (COALESCE(own.domain, common.domain) = 'PET' OR current_type.domain = 'PET')
          AND (current_type.id IS NULL OR current_type.domain = 'PET')
    ), vote AS (
        SELECT pet_product.id,
               other_assignment.product_type_id,
               COUNT(*) AS votes
        FROM pet_product
        JOIN app.retailer_product AS other
          ON other.barcode = pet_product.barcode
         AND other.id <> pet_product.id
        JOIN app.retailer_product_type AS other_assignment
          ON other_assignment.retailer_product_id = other.id
        JOIN app.product_type AS other_type
          ON other_type.id = other_assignment.product_type_id
         AND other_type.domain = 'PET'
        GROUP BY pet_product.id, other_assignment.product_type_id
    ), tally AS (
        SELECT vote.id,
               vote.product_type_id,
               vote.votes,
               SUM(vote.votes) OVER (PARTITION BY vote.id) AS all_votes,
               ROW_NUMBER() OVER (PARTITION BY vote.id ORDER BY vote.votes DESC, vote.product_type_id) AS place
        FROM vote
    ), decision AS (
        SELECT pet_product.id,
               tally.product_type_id,
               tally.votes,
               tally.all_votes - tally.votes
                   + CASE WHEN pet_product.current_type_id IS NULL THEN 0 ELSE 1 END AS against
        FROM pet_product
        JOIN tally
          ON tally.id = pet_product.id
         AND tally.place = 1
        WHERE pet_product.current_type_id IS DISTINCT FROM tally.product_type_id
    )
    INSERT INTO app.retailer_product_type AS assignment (
        retailer_product_id,
        product_type_id,
        confidence,
        assignment_source,
        evidence,
        algorithm_version
    )
    SELECT decision.id,
           decision.product_type_id,
           0.9500,
           'BARCODE_MAJORITY',
           'Isti barkod kod drugih lanaca: ' || decision.votes || ' prema ' || decision.against,
           'taxonomy-v22'
    FROM decision
    WHERE decision.votes >= 3
      AND decision.votes >= 3 * decision.against
      AND NOT EXISTS (
          SELECT 1
          FROM app.retailer_product_type_rejection AS rejected
          WHERE rejected.retailer_product_id = decision.id
            AND rejected.product_type_id = decision.product_type_id
      )
    ON CONFLICT (retailer_product_id) DO UPDATE SET
        product_type_id = EXCLUDED.product_type_id,
        confidence = EXCLUDED.confidence,
        assignment_source = EXCLUDED.assignment_source,
        evidence = EXCLUDED.evidence,
        algorithm_version = EXCLUDED.algorithm_version,
        updated_at = NOW()
    WHERE assignment.reviewed = FALSE
    RETURNING assignment.retailer_product_id;
END;
$function$;

CREATE TEMPORARY TABLE pet_switch ON COMMIT DROP AS
SELECT changed_product_id AS retailer_product_id
FROM app.assign_pet_kinds_by_barcode(NULL);

-- Predlog stare vrste više ne čeka pregled.
DELETE FROM app.product_type_candidate AS candidate
USING pet_switch AS switch,
      app.retailer_product_type AS assignment
WHERE candidate.retailer_product_id = switch.retailer_product_id
  AND assignment.retailer_product_id = switch.retailer_product_id
  AND candidate.product_type_id <> assignment.product_type_id
  AND candidate.status = 'PENDING';

-- Porodica sa promenjenim proizvodom pokazuje vrstu koju ima većina.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM pet_switch AS switch
    JOIN app.retailer_product AS product
      ON product.id = switch.retailer_product_id
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
