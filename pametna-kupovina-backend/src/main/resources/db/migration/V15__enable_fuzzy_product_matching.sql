CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE INDEX idx_canonical_product_normalized_name_trgm
    ON app.canonical_product
    USING GIN (normalized_name public.gin_trgm_ops)
    WHERE normalized_name IS NOT NULL;
