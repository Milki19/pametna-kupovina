-- IDEA je 09.09. prestala da objavljuje pet cenovnika „IDEA MARKETI_Cenovnik …"
-- (Rplus, Rbase, I0, Iplus, Iminus) i prešla na IDEA/RODA/MERCATOR. Njihovih
-- 98.560 ponuda je ostalo u tabeli: nijedno aktivno mapiranje ne pokazuje na
-- njih, pa ne ulaze ni u jednu preporuku, ali kvare svaku statistiku.
-- Provereno na kopiji produkcijske baze: plan za 21 stavku kod Karaburme
-- (sve tri varijante i 20 prodavnica) isti je pre i posle.

DELETE FROM app.current_price_offer AS offer
USING app.retailer_product AS product, app.retailer AS retailer
WHERE product.id = offer.retailer_product_id
  AND retailer.id = product.retailer_id
  AND retailer.code = 'IDEA_RODA'
  AND offer.scope_type = 'STORE_FORMAT'
  AND offer.retailer_format_name LIKE 'IDEA MARKETI\_Cenovnik %'
  AND offer.last_seen_date < DATE '2026-09-10'
  AND NOT EXISTS (
      SELECT 1
      FROM app.store_price_format_mapping AS mapping
      WHERE mapping.retailer_id = product.retailer_id
        AND mapping.active
        AND LOWER(BTRIM(mapping.retailer_format_name))
            = LOWER(BTRIM(offer.retailer_format_name))
  );
