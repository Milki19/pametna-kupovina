ALTER TABLE app.price_observation
    ADD COLUMN store_id BIGINT;


ALTER TABLE app.price_observation
    ADD CONSTRAINT fk_price_observation_store
        FOREIGN KEY (store_id)
            REFERENCES app.store (id);


ALTER TABLE app.price_observation
    DROP CONSTRAINT uq_price_observation_product_date_format;


ALTER TABLE app.price_observation
    ADD CONSTRAINT uq_price_observation_product_date_scope
        UNIQUE NULLS NOT DISTINCT (
            retailer_product_id,
            price_date,
            retailer_format_name,
            store_id
        );


CREATE INDEX idx_price_observation_store_product_date
    ON app.price_observation (
        store_id,
        retailer_product_id,
        price_date DESC,
        id DESC
    )
    WHERE store_id IS NOT NULL;


CREATE INDEX idx_price_observation_format_product_date
    ON app.price_observation (
        retailer_format_name,
        retailer_product_id,
        price_date DESC,
        id DESC
    )
    WHERE store_id IS NULL
      AND retailer_format_name IS NOT NULL;
