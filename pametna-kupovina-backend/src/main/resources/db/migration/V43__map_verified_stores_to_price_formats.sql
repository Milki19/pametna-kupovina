CREATE TABLE app.store_price_format_mapping (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_id BIGINT NOT NULL,
    source_store_code VARCHAR(50) NOT NULL,
    store_external_code VARCHAR(100),
    retailer_format_name VARCHAR(200) NOT NULL,
    verification_status VARCHAR(30) NOT NULL,
    mapping_method VARCHAR(60) NOT NULL,
    source_url TEXT NOT NULL,
    source_last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_store_price_format_mapping_retailer
        FOREIGN KEY (retailer_id) REFERENCES app.retailer (id),
    CONSTRAINT uq_store_price_format_mapping_source
        UNIQUE (retailer_id, source_store_code),
    CONSTRAINT chk_store_price_format_mapping_source_code
        CHECK (BTRIM(source_store_code) <> ''),
    CONSTRAINT chk_store_price_format_mapping_store_code
        CHECK (
            store_external_code IS NULL
            OR BTRIM(store_external_code) <> ''
        ),
    CONSTRAINT chk_store_price_format_mapping_format
        CHECK (BTRIM(retailer_format_name) <> ''),
    CONSTRAINT chk_store_price_format_mapping_status
        CHECK (verification_status IN (
            'VERIFIED',
            'UNLINKED',
            'REVIEW_REQUIRED'
        )),
    CONSTRAINT chk_store_price_format_mapping_method
        CHECK (BTRIM(mapping_method) <> ''),
    CONSTRAINT chk_store_price_format_mapping_source_url
        CHECK (BTRIM(source_url) <> ''),
    CONSTRAINT chk_store_price_format_mapping_link
        CHECK (
            verification_status <> 'VERIFIED'
            OR store_external_code IS NOT NULL
        ),
    CONSTRAINT chk_store_price_format_mapping_timestamps
        CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_store_price_format_mapping_verified_store
    ON app.store_price_format_mapping (
        retailer_id,
        store_external_code
    )
    WHERE active = TRUE
      AND verification_status = 'VERIFIED'
      AND store_external_code IS NOT NULL;

CREATE INDEX idx_store_price_format_mapping_lookup
    ON app.store_price_format_mapping (
        retailer_id,
        store_external_code,
        retailer_format_name
    )
    WHERE active = TRUE
      AND verification_status = 'VERIFIED';

-- IDEA lokator ne objavljuje MP šifru. Za valjevsku prodavnicu je veza sa
-- MP405 ranije ručno potvrđena, a zvanični pregled cenovnika po objektima
-- sada nedvosmisleno kaže da MP405 koristi Iplus, ne I0.
INSERT INTO app.store_price_format_mapping (
    retailer_id,
    source_store_code,
    store_external_code,
    retailer_format_name,
    verification_status,
    mapping_method,
    source_url
)
SELECT retailer.id,
       'MP405',
       'IDEA_GEO_44_270271_19_886746',
       'IDEA MARKETI_Cenovnik Iplus',
       'VERIFIED',
       'MANUAL_OFFICIAL_CROSS_REFERENCE',
       'https://data.gov.rs/s/resources/cenovnici-proizvoda-prema-pravilniku-o-uslovima-sadrzaju-i-nacinu-objavljivanja-cenovnika-idea-marketi-doo/20260901-131508/idea-marketi-maloprodajni-objekti.xlsx'
FROM app.retailer AS retailer
WHERE retailer.code = 'IDEA_RODA'
ON CONFLICT (retailer_id, source_store_code) DO NOTHING;

-- Komercijalni tip prodavnice i cenovna grupa više nisu ista stvar.
-- Vraćamo IDEA Valjevo na lokacijski format; zasebna tabela iznad čuva Iplus.
UPDATE app.store AS store
SET store_format_id = location_format.id,
    pricing_eligible = FALSE,
    pricing_ineligibility_reason = 'PRICE_FORMAT_HAS_NO_PRICES',
    updated_at = NOW()
FROM app.retailer AS retailer,
     app.store_format AS location_format
WHERE retailer.id = store.retailer_id
  AND retailer.code = 'IDEA_RODA'
  AND store.external_code = 'IDEA_GEO_44_270271_19_886746'
  AND location_format.retailer_id = retailer.id
  AND location_format.code = 'IDEA_LOCATION';

-- Postojeća sandbox/produkcijska baza možda već ima sveže Iplus cene.
-- U tom slučaju prodavnica odmah postaje podobna; u praznoj bazi ostaje
-- bezbedno isključena do prvog uspešnog uvoza cena.
UPDATE app.store AS store
SET pricing_eligible = TRUE,
    pricing_ineligibility_reason = NULL,
    updated_at = NOW()
FROM app.store_price_format_mapping AS mapping
WHERE mapping.retailer_id = store.retailer_id
  AND mapping.store_external_code = store.external_code
  AND mapping.active = TRUE
  AND mapping.verification_status = 'VERIFIED'
  AND store.active = TRUE
  AND store.location IS NOT NULL
  AND store.geocoding_status IN ('AUTO_VERIFIED', 'MANUALLY_VERIFIED')
  AND EXISTS (
      SELECT 1
      FROM app.current_price_offer AS offer
      JOIN app.retailer_product AS product
        ON product.id = offer.retailer_product_id
      WHERE product.retailer_id = store.retailer_id
        AND offer.scope_type = 'STORE_FORMAT'
        AND LOWER(BTRIM(offer.retailer_format_name)) =
            LOWER(BTRIM(mapping.retailer_format_name))
  );
