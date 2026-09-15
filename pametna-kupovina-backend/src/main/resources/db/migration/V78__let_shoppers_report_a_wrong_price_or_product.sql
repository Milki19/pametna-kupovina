-- "Prijavi grešku" on the product screen, as on Cenoteka: a shopper says a
-- price is wrong or two listings are not the same product, and the owner
-- reviews it before anything changes.
CREATE TABLE app.product_report (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canonical_product_id BIGINT NOT NULL
        REFERENCES app.canonical_product (id) ON DELETE CASCADE,
    retailer_product_id BIGINT
        REFERENCES app.retailer_product (id) ON DELETE SET NULL,
    reason VARCHAR(30) NOT NULL
        CHECK (reason IN ('WRONG_PRICE', 'NOT_SAME_PRODUCT', 'OTHER')),
    note VARCHAR(500),
    client_token_hash VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'CONFIRMED', 'REJECTED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_product_report_status
    ON app.product_report (status, created_at DESC);
