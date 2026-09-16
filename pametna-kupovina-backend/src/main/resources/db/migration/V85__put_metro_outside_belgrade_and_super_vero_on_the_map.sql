-- Seven more shops priced from their own lists but never on a route: METRO
-- outside Belgrade (Kragujevac, Niš, Novi Sad, Palić, Požarevac, Šabac) and
-- Super Vero 1 in Novi Beograd. Addresses come from the chains' published
-- store lists (retailserbia.com for METRO, whose own locator refuses automated
-- access; supervero.rs for Super Vero), coordinates from the METRO and Super
-- Vero shops mapped in OpenStreetMap at those addresses (16.09.2026).
-- Super Vero publishes one list, "Veropoulos d.o.o. OJ1"; OJ1 is read as its
-- organisational unit 1, Super Vero 1, and the other six shops stay off the
-- map until that is confirmed. "Metro CashCarry - ST" names no town and stays
-- a price list without a location. Univerexport's five lists are price zones,
-- not shops, and stay as they are.

INSERT INTO app.store_format (retailer_id, code, name)
SELECT retailer.id, 'SUPER_VERO', 'Super Vero'
FROM app.retailer AS retailer
WHERE retailer.code = 'VEROPOULOS'
  AND NOT EXISTS (
      SELECT 1 FROM app.store_format AS format
      WHERE format.retailer_id = retailer.id AND format.code = 'SUPER_VERO'
  );

CREATE TEMP TABLE located_shop (
    retailer_code TEXT,
    format_code TEXT,
    external_code TEXT,
    name TEXT,
    address TEXT,
    city TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    address_source TEXT,
    price_list TEXT,
    price_list_source TEXT
) ON COMMIT DROP;

INSERT INTO located_shop VALUES
    ('METRO', 'METRO_CASH_CARRY', 'METRO_KRAGUJEVAC', 'METRO Kragujevac', 'Lepenički bulevar 1a', 'Kragujevac',
     44.0203554, 20.9292222, 'https://retailserbia.com/maloprodaja/supermarketi/69-metrocacveleprodajemaloprodajecentri',
     'Metro CashCarry - ST Kragujevac', 'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/'),
    ('METRO', 'METRO_CASH_CARRY', 'METRO_NIS', 'METRO Niš', 'Naselje Milke Protić 1', 'Niš',
     43.3184236, 21.8424525, 'https://retailserbia.com/maloprodaja/supermarketi/69-metrocacveleprodajemaloprodajecentri',
     'Metro CashCarry - ST Nis', 'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/'),
    ('METRO', 'METRO_CASH_CARRY', 'METRO_NOVI_SAD', 'METRO Novi Sad', 'Put Novosadskog partizanskog odreda 5', 'Novi Sad',
     45.2715900, 19.8194950, 'https://retailserbia.com/maloprodaja/supermarketi/69-metrocacveleprodajemaloprodajecentri',
     'Metro CashCarry - ST NoviSad', 'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/'),
    ('METRO', 'METRO_CASH_CARRY', 'METRO_PALIC', 'METRO Palić', 'Tuk Ugarnice bb', 'Palić',
     46.1020199, 19.7110935, 'https://retailserbia.com/maloprodaja/supermarketi/69-metrocacveleprodajemaloprodajecentri',
     'Metro CashCarry - ST Palic', 'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/'),
    ('METRO', 'METRO_CASH_CARRY', 'METRO_POZAREVAC', 'METRO Požarevac', 'Đure Đakovića bb', 'Požarevac',
     44.6088583, 21.1685753, 'https://retailserbia.com/maloprodaja/supermarketi/69-metrocacveleprodajemaloprodajecentri',
     'Metro CashCarry - ST Pozarevac', 'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/'),
    ('METRO', 'METRO_CASH_CARRY', 'METRO_SABAC', 'METRO Šabac', 'Gavrila Principa bb', 'Šabac',
     44.7763004, 19.6836508, 'https://retailserbia.com/maloprodaja/supermarketi/69-metrocacveleprodajemaloprodajecentri',
     'Metro CashCarry - ST Sabac', 'https://data.gov.rs/sr/datasets/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-metro/'),
    ('VEROPOULOS', 'SUPER_VERO', 'SUPER_VERO_1', 'Super Vero 1 Novi Beograd', 'Bulevar Milutina Milankovića 86a', 'Beograd',
     44.8100832, 20.4173836, 'https://www.supervero.rs/en/super-vero-stores/',
     'Veropoulos d.o.o. OJ1', 'https://data.gov.rs/sr/datasets/');

INSERT INTO app.store (
    retailer_id, external_code, name, address, city, store_format_id,
    active, location, geocoding_candidate, geocoding_status, geocoding_source,
    geocoding_source_reference, geocoding_query, geocoding_matched_address,
    geocoding_confidence, geocoded_at, geocoding_reviewed_at, verified_at,
    pricing_eligible, pricing_ineligibility_reason
)
SELECT retailer.id,
       shop.external_code,
       shop.name,
       shop.address,
       shop.city,
       format.id,
       TRUE,
       ST_SetSRID(ST_MakePoint(shop.longitude, shop.latitude), 4326)::geography,
       ST_SetSRID(ST_MakePoint(shop.longitude, shop.latitude), 4326)::geography,
       'AUTO_VERIFIED',
       'OPENSTREETMAP_SHOP',
       'https://www.openstreetmap.org/?mlat=' || shop.latitude || '&mlon=' || shop.longitude
           || ' ; ' || shop.address_source,
       shop.name || ', ' || shop.city,
       shop.address || ', ' || shop.city,
       0.9000,
       NOW(),
       NOW(),
       NOW(),
       FALSE,
       'NOT_REVIEWED'
FROM located_shop AS shop
JOIN app.retailer AS retailer
  ON retailer.code = shop.retailer_code
JOIN app.store_format AS format
  ON format.retailer_id = retailer.id
 AND format.code = shop.format_code
WHERE NOT EXISTS (
    SELECT 1 FROM app.store AS existing
    WHERE existing.retailer_id = retailer.id
      AND existing.external_code = shop.external_code
);

-- Each located shop reads the price list published under its name.
INSERT INTO app.store_price_format_mapping (
    retailer_id, source_store_code, store_external_code, retailer_format_name,
    verification_status, mapping_method, source_url, source_last_seen_at, active
)
SELECT retailer.id,
       shop.retailer_code || '_STORE:' || shop.external_code,
       shop.external_code,
       shop.price_list,
       'VERIFIED',
       'PUBLISHED_PRICE_LIST_PER_STORE',
       shop.price_list_source,
       NOW(),
       TRUE
FROM located_shop AS shop
JOIN app.retailer AS retailer
  ON retailer.code = shop.retailer_code
ON CONFLICT (retailer_id, source_store_code) DO UPDATE SET
    retailer_format_name = EXCLUDED.retailer_format_name,
    verification_status = 'VERIFIED',
    mapping_method = EXCLUDED.mapping_method,
    active = TRUE,
    updated_at = NOW();

-- The same list must not also stay a note about an unknown location.
UPDATE app.store_price_format_mapping AS mapping
SET active = FALSE, updated_at = NOW()
FROM app.store AS entry, located_shop AS shop
WHERE mapping.store_external_code = entry.external_code
  AND mapping.retailer_id = entry.retailer_id
  AND mapping.mapping_method = 'PRICE_LIST_WITHOUT_LOCATION'
  AND entry.location IS NULL
  AND entry.name = shop.price_list;

UPDATE app.store AS entry
SET active = FALSE, updated_at = NOW()
FROM located_shop AS shop
WHERE entry.location IS NULL
  AND entry.name = shop.price_list;

-- Usable in a plan right away when the chain's list already has prices for
-- the shop; the next import confirms it the usual way.
UPDATE app.store AS store
SET pricing_eligible = TRUE,
    pricing_ineligibility_reason = NULL,
    updated_at = NOW()
FROM located_shop AS shop
JOIN app.retailer AS retailer
  ON retailer.code = shop.retailer_code
WHERE store.retailer_id = retailer.id
  AND store.external_code = shop.external_code
  AND store.active = TRUE
  AND EXISTS (
      SELECT 1
      FROM app.current_price_offer AS offer
      JOIN app.retailer_product AS product
        ON product.id = offer.retailer_product_id
       AND product.retailer_id = retailer.id
      WHERE offer.scope_type = 'STORE_FORMAT'
        AND LOWER(BTRIM(offer.retailer_format_name)) = LOWER(BTRIM(shop.price_list))
  );
