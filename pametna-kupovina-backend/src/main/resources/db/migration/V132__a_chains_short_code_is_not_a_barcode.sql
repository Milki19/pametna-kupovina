-- Transkom's "PAPRIKA BABURA II KLASA" stood in one product with BB Trade's
-- "SIR TRAPIST 45% PALADIN 3.3KG": both chains put the code 0000000002288 in
-- the barcode column. It passes the check digit, but it is each chain's own
-- short code padded with zeros, so it names another product in every chain,
-- and a pepper made that trapist block a product the cheese rule (V130)
-- rightly left alone. A barcode has at least eight digits after its leading
-- zeros; the import no longer joins chains by a shorter one
-- (ExactEanMatcher), and the products already joined by one stand by their
-- own names again. The codes stay on the products.

CREATE TEMP TABLE chain_code_product ON COMMIT DROP AS
SELECT product.id, product.canonical_product_id
FROM app.retailer_product AS product
JOIN app.canonical_product AS canonical
  ON canonical.id = product.canonical_product_id
WHERE canonical.barcode ~ '^[0-9]+$'
  AND LENGTH(LTRIM(canonical.barcode, '0')) < 8;

DELETE FROM app.product_family_member AS member
WHERE member.canonical_product_id IN (SELECT canonical_product_id FROM chain_code_product)
  AND member.relation_type <> 'MANUAL';

UPDATE app.retailer_product AS product
SET canonical_product_id = NULL
WHERE product.id IN (SELECT id FROM chain_code_product);

-- The products are grouped again by their own names, blocks of one cheese
-- join (V130), and they are grouped again with those decisions.
SELECT app.assign_product_families(NULL);

SELECT app.join_blocks_of_one_cheese();

SELECT app.assign_product_families(NULL);

SELECT app.refresh_family_display_names();

SELECT app.refresh_typical_prices();

-- What each chain sells of each family, as the import counts it.
DELETE FROM app.product_retailer_presence;

INSERT INTO app.product_retailer_presence (
    product_family_id,
    retailer_id,
    first_seen_date,
    last_seen_date,
    latest_price_date,
    current_offer_count,
    store_count,
    format_count,
    minimum_effective_price
)
SELECT product.product_family_id,
       product.retailer_id,
       MIN(offer.first_seen_date),
       MAX(offer.last_seen_date),
       MAX(offer.price_date),
       COUNT(*)::INTEGER,
       COUNT(DISTINCT offer.store_id)
           FILTER (WHERE offer.store_id IS NOT NULL)::INTEGER,
       COUNT(DISTINCT LOWER(BTRIM(offer.retailer_format_name))) FILTER (
           WHERE NULLIF(BTRIM(offer.retailer_format_name), '') IS NOT NULL
       )::INTEGER,
       MIN(app.effective_price(
           offer.regular_price,
           offer.discounted_price,
           offer.discount_start,
           offer.discount_end,
           app.market_today(chain.market_id)
       ))
FROM app.current_price_offer AS offer
JOIN app.retailer_product AS product
  ON product.id = offer.retailer_product_id
JOIN app.retailer AS chain
  ON chain.id = product.retailer_id
WHERE product.product_family_id IS NOT NULL
  AND app.in_latest_price_list(
      product.retailer_id,
      offer.scope_key,
      offer.price_date,
      app.market_today(chain.market_id)
  )
  AND product.package_count =
      app.family_base_package_count(product.product_family_id)
GROUP BY product.product_family_id, product.retailer_id;

REFRESH MATERIALIZED VIEW app.current_sale_list;
