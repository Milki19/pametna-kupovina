-- M1 odvaja lokaciju koju možemo da prikažemo od lokacije za koju je
-- potvrđeno da cenovnik zaista važi. Neproverena veza nikada ne sme da
-- utiče na preporuku kupovine.
ALTER TABLE app.store
    ADD COLUMN pricing_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN pricing_ineligibility_reason VARCHAR(100)
        DEFAULT 'NOT_REVIEWED';

ALTER TABLE app.store
    ADD CONSTRAINT chk_store_pricing_eligibility
        CHECK (
            (pricing_eligible AND pricing_ineligibility_reason IS NULL)
            OR (
                NOT pricing_eligible
                AND NULLIF(BTRIM(pricing_ineligibility_reason), '')
                    IS NOT NULL
            )
        );

-- Lidl i DIS imaju zvanične lokacije i proverenu vezu formata. Europrom
-- trenutno objavljuje jedan format koji važi za njegove potvrđene objekte.
UPDATE app.store AS store
SET pricing_eligible = TRUE,
    pricing_ineligibility_reason = NULL
FROM app.retailer AS retailer,
     app.store_format AS format
WHERE retailer.id = store.retailer_id
  AND format.id = store.store_format_id
  AND store.active = TRUE
  AND store.location IS NOT NULL
  AND store.geocoding_status IN ('AUTO_VERIFIED', 'MANUALLY_VERIFIED')
  AND (
      (
          retailer.code IN ('LIDL', 'DIS')
          AND store.data_source_id IS NOT NULL
      )
      OR (
          retailer.code = 'EUROPROM'
          AND format.code = 'EUROPROM'
      )
  );

-- Maxi je store-level izvor. Samo objekat koji se već pojavljuje u
-- store-level cenovniku ima dokazanu vezu lokacija-cena.
UPDATE app.store AS store
SET pricing_eligible = TRUE,
    pricing_ineligibility_reason = NULL
FROM app.retailer AS retailer
WHERE retailer.id = store.retailer_id
  AND retailer.code = 'MAXI'
  AND store.active = TRUE
  AND store.location IS NOT NULL
  AND store.geocoding_status IN ('AUTO_VERIFIED', 'MANUALLY_VERIFIED')
  AND (
      EXISTS (
          SELECT 1
          FROM app.current_price_offer AS offer
          WHERE offer.store_id = store.id
            AND offer.scope_type = 'STORE'
      )
      OR EXISTS (
          SELECT 1
          FROM app.price_observation AS observation
          WHERE observation.store_id = store.id
      )
  );

UPDATE app.store
SET pricing_ineligibility_reason = CASE
        WHEN NOT active THEN 'INACTIVE'
        WHEN location IS NULL THEN 'MISSING_COORDINATES'
        WHEN geocoding_status NOT IN (
            'AUTO_VERIFIED',
            'MANUALLY_VERIFIED'
        ) THEN 'LOCATION_NOT_VERIFIED'
        ELSE 'PRICE_FORMAT_NOT_VERIFIED'
    END
WHERE NOT pricing_eligible;

CREATE INDEX idx_store_pricing_candidate
    ON app.store (retailer_id, store_format_id, id)
    WHERE active = TRUE
      AND pricing_eligible = TRUE
      AND location IS NOT NULL;


-- Pragovi i brojači čine zdravlje izvora merljivim. Pragovi su namerno
-- konzervativni i mogu se menjati bez nove migracije.
ALTER TABLE app.retailer_data_source
    ADD COLUMN expected_min_rows_saved INTEGER,
    ADD COLUMN max_success_age_hours INTEGER,
    ADD COLUMN minimum_volume_ratio NUMERIC(5, 4)
        NOT NULL DEFAULT 0.6000,
    ADD COLUMN last_rows_read INTEGER,
    ADD COLUMN last_rows_saved INTEGER,
    ADD COLUMN consecutive_success_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN consecutive_failure_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE app.retailer_data_source
    ADD CONSTRAINT chk_retailer_source_expected_rows
        CHECK (expected_min_rows_saved IS NULL OR expected_min_rows_saved > 0),
    ADD CONSTRAINT chk_retailer_source_max_age
        CHECK (max_success_age_hours IS NULL OR max_success_age_hours > 0),
    ADD CONSTRAINT chk_retailer_source_volume_ratio
        CHECK (minimum_volume_ratio > 0 AND minimum_volume_ratio <= 1),
    ADD CONSTRAINT chk_retailer_source_last_rows_read
        CHECK (last_rows_read IS NULL OR last_rows_read >= 0),
    ADD CONSTRAINT chk_retailer_source_last_rows_saved
        CHECK (last_rows_saved IS NULL OR last_rows_saved >= 0),
    ADD CONSTRAINT chk_retailer_source_success_count
        CHECK (consecutive_success_count >= 0),
    ADD CONSTRAINT chk_retailer_source_failure_count
        CHECK (consecutive_failure_count >= 0);

UPDATE app.retailer_data_source AS source
SET expected_min_rows_saved = policy.minimum_rows,
    max_success_age_hours = policy.maximum_age_hours,
    minimum_volume_ratio = policy.minimum_ratio
FROM app.retailer AS retailer
JOIN (
    VALUES
        ('EUROPROM', 'PRICE_CATALOG', 5000, 48, 0.6000::NUMERIC),
        ('LIDL', 'PRICE_CATALOG', 2000, 48, 0.6000::NUMERIC),
        ('IDEA_RODA', 'PRICE_CATALOG', 10000, 48, 0.6000::NUMERIC),
        ('UNIVEREXPORT', 'PRICE_CATALOG', 10000, 48, 0.6000::NUMERIC),
        ('DIS', 'PRICE_CATALOG', 15000, 48, 0.6000::NUMERIC),
        ('MAXI', 'PRICE_CATALOG', 1000, 48, 0.5000::NUMERIC),
        ('LIDL', 'STORE_LOCATIONS', 80, 192, 0.8000::NUMERIC),
        ('DIS', 'STORE_LOCATIONS', 45, 192, 0.8000::NUMERIC),
        ('MAXI', 'STORE_LOCATIONS', 500, 192, 0.8000::NUMERIC),
        ('IDEA_RODA', 'STORE_LOCATIONS', 220, 192, 0.8000::NUMERIC),
        ('UNIVEREXPORT', 'STORE_LOCATIONS', 180, 192, 0.8000::NUMERIC)
) AS policy(
    retailer_code,
    source_type,
    minimum_rows,
    maximum_age_hours,
    minimum_ratio
) ON retailer.code = policy.retailer_code
WHERE source.retailer_id = retailer.id
  AND source.source_type = policy.source_type;

-- Postojeće stanje se inicijalizuje iz poslednjeg završenog importa da
-- izveštaj bude koristan odmah nakon migracije.
WITH latest_run AS (
    SELECT DISTINCT ON (run.data_source_id)
           run.data_source_id,
           run.rows_read,
           run.rows_saved
    FROM app.import_run AS run
    WHERE run.data_source_id IS NOT NULL
      AND run.status <> 'RUNNING'
    ORDER BY run.data_source_id, run.started_at DESC, run.id DESC
)
UPDATE app.retailer_data_source AS source
SET last_rows_read = latest.rows_read,
    last_rows_saved = latest.rows_saved,
    consecutive_success_count = CASE
        WHEN source.last_status IN ('SUCCEEDED', 'SUCCEEDED_WITH_ERRORS')
            THEN 1
        ELSE 0
    END,
    consecutive_failure_count = CASE
        WHEN source.last_status = 'FAILED' THEN 1
        ELSE 0
    END
FROM latest_run AS latest
WHERE latest.data_source_id = source.id;


-- Napredak omogućava da razlikujemo spor import od zaglavljenog importa.
ALTER TABLE app.import_run
    ADD COLUMN stage VARCHAR(30) NOT NULL DEFAULT 'STARTING',
    ADD COLUMN downloaded_bytes BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_progress_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

UPDATE app.import_run
SET stage = CASE
        WHEN status = 'RUNNING' THEN 'STARTING'
        WHEN status = 'FAILED' THEN 'FAILED'
        ELSE 'COMPLETED'
    END,
    last_progress_at = COALESCE(finished_at, started_at);

ALTER TABLE app.import_run
    ADD CONSTRAINT chk_import_run_stage
        CHECK (stage IN (
            'STARTING',
            'DOWNLOADING',
            'SCANNING',
            'WRITING',
            'REFRESHING_CATALOG',
            'COMPLETED',
            'FAILED'
        )),
    ADD CONSTRAINT chk_import_run_downloaded_bytes
        CHECK (downloaded_bytes >= 0);

CREATE INDEX idx_import_run_active_progress
    ON app.import_run (last_progress_at, id)
    WHERE status = 'RUNNING';
