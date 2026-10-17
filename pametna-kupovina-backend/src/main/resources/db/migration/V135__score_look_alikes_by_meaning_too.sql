-- "Isti proizvodi?" scored a pair only by shared letters (pg_trgm), so
-- "KOB PILI 500G" and "Pileća kobasica 500 g" looked far apart and two
-- different products that share most letters looked close. A language model
-- (multilingual-e5-small) gives each name a vector; the dot product of two is
-- how close they are in meaning. Measured on a copy (17. 10. 2026.): letters
-- would ask about 307 of 312 pairs already decided as different, meaning
-- about 121, while keeping 90% of pairs with one barcode.

-- Filled by the backend for the families of open suggestions, again only when
-- the name or the model changes.
CREATE TABLE app.product_family_meaning (
    product_family_id BIGINT PRIMARY KEY
        REFERENCES app.product_family (id) ON DELETE CASCADE,
    model_code VARCHAR(100) NOT NULL,
    name_text VARCHAR(500) NOT NULL,
    vector REAL[] NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- NULL until the backend scores the pair, and always without a model.
ALTER TABLE app.product_merge_suggestion
    ADD COLUMN meaning_score NUMERIC(5, 4);
