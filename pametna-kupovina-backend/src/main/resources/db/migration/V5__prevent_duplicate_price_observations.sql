ALTER TABLE app.price_observation
    ADD CONSTRAINT uq_price_observation_product_date_format
        UNIQUE NULLS NOT DISTINCT (
    retailer_product_id,
    price_date,
    retailer_format_name
    );