-- The list of sales read app.current_sale (V115) on every request: each
-- offer with a discounted price went through the newest-list, package and
-- price-check rules again, which took 14 to 21 seconds on production. The
-- same rows are kept here, ready, and the backend refreshes them after an
-- import and at least every hour, so a sale that ends at midnight drops off
-- within the hour.
CREATE MATERIALIZED VIEW app.current_sale_list AS
SELECT *
FROM app.current_sale
WITH DATA;

-- One row per offer; a concurrent refresh needs a unique index and lets the
-- list be read while it runs.
CREATE UNIQUE INDEX ux_current_sale_list_offer
    ON app.current_sale_list (offer_id);

CREATE INDEX ix_current_sale_list_family
    ON app.current_sale_list (product_family_id);

CREATE INDEX ix_current_sale_list_retailer
    ON app.current_sale_list (retailer_id);

CREATE INDEX ix_current_sale_list_store
    ON app.current_sale_list (store_id)
    WHERE store_id IS NOT NULL;
