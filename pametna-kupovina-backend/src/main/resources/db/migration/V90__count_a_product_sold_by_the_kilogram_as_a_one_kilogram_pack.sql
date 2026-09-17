-- Loose goods have no size: METRO "JABUKA JONAGORED", a pork neck from the
-- counter, IDEA "SIR KAČKAVALJ K PLUS 35%MM RNF". A plan gives an amount such
-- as "jabuke 2kg" or "ćevapi 3kg" only packs that add up to it, so these never
-- took part. The owner decided (nastavak 29) that a product sold by the
-- kilogram counts as a pack of 1 kg when the list gives an amount.
--
-- Sold by the kilogram: the chain gives no size, writes a kilogram as the unit
-- of measure, and every price list states the price for that unit as the price
-- itself (IDEA, METRO, Veropoulos, Univerexport and Europrom do so for 98-100%
-- of such fruit, vegetables, meat and fish, checked 17.09.). The unit alone
-- does not decide: Maxi writes "kg" for "Mango komad", and its shop price
-- lists give 628,54 a kilogram against 219,99 for the piece. Maxi's catalogue
-- (Delhaize) repeats the price as the unit price for every product, packed or
-- not, so a product stays out when any price list says otherwise, and so does
-- a name that says a piece ("Jabuka Zlatni delises komad" 19,99, METRO
-- "AVOKADO KOM", "KIVI KORPICA", "Rotkvice veza", a basil pot) or carries a
-- size the name reader missed ("mesa400g", "Danubius5kg", "Perla 20caps").
CREATE FUNCTION app.sold_by_the_kilogram(target_product_id BIGINT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $function$
    SELECT COALESCE(
               product.quantity_value IS NULL
               AND UPPER(BTRIM(product.unit)) IN ('KG', 'KGR')
               AND product.normalized_name !~ '\m(kom|komad|komada|komadi|korpica|korpice|veza|vezica|saksija|glavica)\M'
               AND product.normalized_name !~ '\m[0-9]+ (g|gr|grama|kg|kgr|ml|l|caps|kaps|gx)\M'
               AND (
                   SELECT BOOL_AND(
                              COALESCE(
                                  offer.unit_price = offer.regular_price
                                  OR offer.unit_price = offer.discounted_price,
                                  FALSE
                              )
                          )
                   FROM app.current_price_offer AS offer
                   WHERE offer.retailer_product_id = product.id
               ),
               FALSE
           )
    FROM app.retailer_product AS product
    WHERE product.id = target_product_id
$function$;

-- A grill mix of neck, kebab and bacon is not pork neck. Sold by the
-- kilogram, METRO's "GRILL MIX 3 MAP XL-VRAT,KARE,SLANIN SK" became the
-- cheapest "vrat 2kg".
UPDATE app.product_type_rule AS rule
SET exclude_pattern = rule.exclude_pattern || '|\m(mix|miks)\M',
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'PORK_NECK_FRESH'
  AND rule.active
  AND rule.retailer_id IS NULL;

DELETE FROM app.product_type_candidate AS candidate
USING app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = candidate.product_type_id
  AND type.code = 'PORK_NECK_FRESH'
  AND product.id = candidate.retailer_product_id
  AND product.normalized_name ~ '\m(mix|miks)\M'
  AND candidate.status = 'PENDING';

DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = assignment.product_type_id
  AND type.code = 'PORK_NECK_FRESH'
  AND product.id = assignment.retailer_product_id
  AND product.normalized_name ~ '\m(mix|miks)\M'
  AND assignment.reviewed = FALSE;

UPDATE app.product_family AS family
SET product_type_id = (
        SELECT assignment.product_type_id
        FROM app.retailer_product AS member
        JOIN app.retailer_product_type AS assignment
          ON assignment.retailer_product_id = member.id
        WHERE member.product_family_id = family.id
        GROUP BY assignment.product_type_id
        ORDER BY COUNT(*) DESC,
                 MAX(assignment.confidence) DESC,
                 assignment.product_type_id
        LIMIT 1
    ),
    updated_at = NOW()
FROM app.product_type AS neck
WHERE neck.code = 'PORK_NECK_FRESH'
  AND family.product_type_id = neck.id
  AND EXISTS (
      SELECT 1
      FROM app.retailer_product AS product
      WHERE product.product_family_id = family.id
        AND product.normalized_name ~ '\m(mix|miks)\M'
  );
