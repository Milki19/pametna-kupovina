-- First pass still let cured products into the fresh grill types: "Dim.pileci
-- file" (dim is an abbreviation the pattern missed), "Seoski vrat 100g" and
-- "Pileca kobasica slajs 100g". A 100g slice of dry neck is not a small pack
-- of grill meat, it is a different product, so it belongs outside these types
-- rather than being ranked lower inside them.

UPDATE app.product_type_rule SET active = FALSE, updated_at = NOW()
WHERE retailer_id IS NULL AND product_type_id IN
    (SELECT id FROM app.product_type WHERE code IN
        ('CHICKEN_FILLET','CHICKEN_DRUMSTICK','CHICKEN_WINGS',
         'PORK_NECK_FRESH','CEVAPI','PLJESKAVICA','GRILL_SAUSAGE'));

INSERT INTO app.product_type_rule(product_type_id, include_pattern, exclude_pattern, priority, confidence)
SELECT t.id, r.inc,
       -- Shared cured / processed vocabulary, plus the per-type extras.
       '\m(dim|dimljen[a-z]*|suv[a-z]*|seosk[a-z]*|domac[a-z]*kobasic[a-z]*|narez[a-z]*|slajs|salam[a-z]*|prsut[a-z]*|sunk[a-z]*|pasteta|pashteta|konzerv[a-z]*|u crevu|crevo|budola|kraski|pronto|lokavsk[a-z]*|kosmajsk[a-z]*|cajn[a-z]*|zimsk[a-z]*|paniran[a-z]*|pohovan[a-z]*|smrznut[a-z]*)\M|' || r.exc,
       5, 0.9950
FROM (VALUES
 ('CHICKEN_FILLET',
  '\m(pilec[a-z]*|pilet[a-z]*|pile)\M.*\m(file|filet[a-z]*)\M|\m(file|filet[a-z]*)\M.*\m(pilec[a-z]*|pilet[a-z]*)\M|\mbelo meso\M',
  '\m(pizza|cips|pringles|zacin|supa|kocka|hrana|sos)\M'),
 ('CHICKEN_DRUMSTICK',
  '\m(batak|bataci|karabatak)\M',
  '\m(curec[a-z]*|zacin|hrana|cips|pringles|supa|kocka|sos)\M'),
 ('CHICKEN_WINGS',
  '\mkrilc[a-z]*\M',
  '\m(cips|pringles|grick[a-z]*|zacin|sos|supa|kocka|hrana)\M'),
 ('PORK_NECK_FRESH',
  '\m(svinjski vrat|vrat svinjski|vratina)\M|\mvrat\M',
  '\m(u mrezi|mreza|valjevac|zacin|hrana)\M'),
 ('CEVAPI',
  '\mcevap[a-z]*\M',
  '\m(zacin|cips|pringles|supa|kocka|hrana|sos)\M'),
 ('PLJESKAVICA',
  '\mpljeskav[a-z]*\M',
  '\m(zacin|povrc[a-z]*|riblj[a-z]*|posn[a-z]*|sos|hrana|cips)\M'),
 ('GRILL_SAUSAGE',
  '\mkobasic[a-z]*\M',
  '\m(zacin|supa|kocka|cips|sos|hrana)\M')
) r(code, inc, exc)
JOIN app.product_type t ON t.code = r.code;

-- Keep the shared prediction query but version the changed rule set for audit.
DO $$
BEGIN
 EXECUTE 'CREATE OR REPLACE VIEW app.product_type_prediction AS ' ||
     replace(pg_get_viewdef('app.product_type_prediction'::regclass, true), 'taxonomy-v5', 'taxonomy-v6');
END $$;
DELETE FROM app.product_type_candidate WHERE status='PENDING';
INSERT INTO app.product_type_candidate(retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version)
SELECT p.retailer_product_id,p.product_type_id,p.confidence,p.prediction_source,p.evidence,p.algorithm_version
FROM app.product_type_prediction p
WHERE p.confidence>=0.75 AND p.confidence<0.95
 AND NOT EXISTS (SELECT 1 FROM app.retailer_product_type t WHERE t.retailer_product_id=p.retailer_product_id AND t.reviewed)
ON CONFLICT DO NOTHING;

-- Refresh only machine classifications; human-reviewed assignments survive.
DELETE FROM app.retailer_product_type WHERE NOT reviewed;
INSERT INTO app.retailer_product_type(retailer_product_id, product_type_id, confidence, assignment_source, evidence, algorithm_version)
SELECT retailer_product_id,product_type_id,confidence,prediction_source,evidence,algorithm_version
FROM app.product_type_prediction p WHERE confidence>=0.95
ON CONFLICT(retailer_product_id) DO NOTHING;
UPDATE app.product_family f SET product_type_id=NULL WHERE NOT EXISTS (
 SELECT 1 FROM app.retailer_product p JOIN app.retailer_product_type t ON t.retailer_product_id=p.id
 WHERE p.product_family_id=f.id AND t.product_type_id=f.product_type_id);
