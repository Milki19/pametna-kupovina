ALTER TABLE app.store
    ADD COLUMN geocoding_candidate GEOGRAPHY(Point, 4326),
    ADD COLUMN geocoding_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN geocoding_query VARCHAR(500),
    ADD COLUMN geocoding_source VARCHAR(100),
    ADD COLUMN geocoding_source_reference VARCHAR(1000),
    ADD COLUMN geocoding_matched_address VARCHAR(500),
    ADD COLUMN geocoding_confidence NUMERIC(5, 4),
    ADD COLUMN geocoding_suspicious_reason VARCHAR(500),
    ADD COLUMN geocoded_at TIMESTAMPTZ,
    ADD COLUMN geocoding_review_note VARCHAR(500),
    ADD COLUMN geocoding_reviewed_at TIMESTAMPTZ;


-- Koordinate koje su postojale pre PK-039 potiču iz kontrolisanog
-- location importa. Obeležavamo ih kao ručno potvrđene umesto da izgubimo
-- poreklo prilikom uvođenja novog workflow-a.
UPDATE app.store
SET geocoding_candidate = location,
    geocoding_status = 'MANUALLY_VERIFIED',
    geocoding_query = LOWER(
        BTRIM(COALESCE(address, name))
    )
        || ', '
        || LOWER(BTRIM(COALESCE(city, 'UNKNOWN'))),
    geocoding_source = 'LOCATION_IMPORT',
    geocoding_matched_address = BTRIM(COALESCE(address, name))
        || ', '
        || BTRIM(COALESCE(city, 'UNKNOWN')),
    geocoding_confidence = 1.0000,
    geocoded_at = created_at,
    geocoding_review_note =
        'Koordinate su postojale pre uvođenja PK-039 workflow-a.',
    geocoding_reviewed_at = created_at
WHERE location IS NOT NULL;


ALTER TABLE app.store
    ADD CONSTRAINT chk_store_geocoding_status
        CHECK (
            geocoding_status IN (
                'PENDING',
                'AUTO_VERIFIED',
                'NEEDS_REVIEW',
                'MANUALLY_VERIFIED',
                'REJECTED'
            )
        ),

    ADD CONSTRAINT chk_store_geocoding_confidence
        CHECK (
            geocoding_confidence IS NULL
            OR (
                geocoding_confidence >= 0
                AND geocoding_confidence <= 1
            )
        ),

    ADD CONSTRAINT chk_store_geocoding_source_not_blank
        CHECK (
            geocoding_source IS NULL
            OR BTRIM(geocoding_source) <> ''
        ),

    ADD CONSTRAINT chk_store_geocoding_query_not_blank
        CHECK (
            geocoding_query IS NULL
            OR BTRIM(geocoding_query) <> ''
        ),

    ADD CONSTRAINT chk_store_geocoding_candidate_metadata
        CHECK (
            geocoding_candidate IS NULL
            OR (
                geocoding_query IS NOT NULL
                AND geocoding_source IS NOT NULL
                AND geocoding_matched_address IS NOT NULL
                AND geocoding_confidence IS NOT NULL
                AND geocoded_at IS NOT NULL
            )
        ),

    ADD CONSTRAINT chk_store_suspicious_location_not_applied
        CHECK (
            geocoding_status NOT IN (
                'NEEDS_REVIEW',
                'REJECTED'
            )
            OR location IS NULL
        ),

    ADD CONSTRAINT chk_store_verified_location_applied
        CHECK (
            geocoding_status NOT IN (
                'AUTO_VERIFIED',
                'MANUALLY_VERIFIED'
            )
            OR (
                geocoding_candidate IS NOT NULL
                AND location IS NOT NULL
            )
        ),

    ADD CONSTRAINT chk_store_geocoding_review_timestamp
        CHECK (
            geocoding_reviewed_at IS NULL
            OR geocoded_at IS NULL
            OR geocoding_reviewed_at >= geocoded_at
        );


CREATE INDEX idx_store_geocoding_review_queue
    ON app.store (city, geocoding_status, id)
    WHERE geocoding_status = 'NEEDS_REVIEW';

CREATE INDEX idx_store_geocoding_candidate
    ON app.store
    USING GIST (geocoding_candidate)
    WHERE geocoding_candidate IS NOT NULL;
