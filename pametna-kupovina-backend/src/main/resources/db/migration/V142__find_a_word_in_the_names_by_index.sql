-- Brzina na 2 jezgra (profil na kopiji baze, 31.10.2026.):
--
-- * Reč koju nijedan rečnik ne zna ("pesak za mačke") tražila se izrazom nad
--   nazivom svakog od 350.000 proizvoda: do 0,6 s po reči u koraku 1 i 0,95 s
--   od 1,05 s plana. Izraz počinje proverama unapred, koje indeks ne čita, pa
--   uz stavku sad stoji i jedna njena reč (wanted_name_hint), koju trigram
--   indeks naziva nalazi odmah. Izraz i dalje odlučuje.
-- * Proizvod bez normalizovanog naziva nalazi se po svom lancu, kao do sada.
-- * Porodice jednog brenda čitale su se celom tabelom porodica.

CREATE INDEX idx_retailer_product_normalized_name_trgm
    ON app.retailer_product USING GIN (normalized_name public.gin_trgm_ops);

CREATE INDEX idx_retailer_product_without_normalized_name
    ON app.retailer_product (retailer_id)
    WHERE normalized_name IS NULL;

CREATE INDEX idx_product_family_brand
    ON app.product_family (brand_id);

ALTER TABLE app.shopping_list_item
    ADD COLUMN wanted_name_hint VARCHAR(600);
