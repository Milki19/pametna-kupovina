-- „Nema u prodavnici“ u kupovini po planu: kupac jednim dodirom javi da
-- proizvoda nema na polici u toj radnji. Cenovnik to ne zna. Kad to u
-- poslednja tri dana jave bar dva različita telefona, preporuka tu ponudu u
-- toj radnji preskače, dok prijave ne zastare. Ovakve prijave ne idu u
-- pregled kod vlasnika; same ističu.

ALTER TABLE app.product_report
    DROP CONSTRAINT product_report_reason_check;

ALTER TABLE app.product_report
    ADD CONSTRAINT product_report_reason_check
        CHECK (reason IN ('WRONG_PRICE', 'NOT_SAME_PRODUCT', 'OTHER', 'NOT_IN_STORE'));

ALTER TABLE app.product_report
    ADD COLUMN store_id BIGINT
        REFERENCES app.store (id) ON DELETE SET NULL;

-- „Nema u prodavnici“ uvek kaže koja ponuda i koja radnja.
ALTER TABLE app.product_report
    ADD CONSTRAINT product_report_not_in_store_names_the_shop
        CHECK (reason <> 'NOT_IN_STORE'
               OR (store_id IS NOT NULL AND retailer_product_id IS NOT NULL));

CREATE INDEX idx_product_report_not_in_store
    ON app.product_report (retailer_product_id, store_id, created_at DESC)
    WHERE reason = 'NOT_IN_STORE';

-- Bar dva različita telefona (bez telefona, svaka prijava za sebe) u tri
-- dana do dana plana.
CREATE FUNCTION app.reported_not_in_store(
    p_retailer_product_id BIGINT,
    p_store_id BIGINT,
    p_as_of DATE
) RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT COUNT(DISTINCT COALESCE(report.client_token_hash, report.id::TEXT)) >= 2
    FROM app.product_report AS report
    WHERE report.reason = 'NOT_IN_STORE'
      AND report.retailer_product_id = p_retailer_product_id
      AND report.store_id = p_store_id
      AND report.created_at >= (p_as_of - 2)::TIMESTAMP AT TIME ZONE 'UTC'
      AND report.created_at < (p_as_of + 1)::TIMESTAMP AT TIME ZONE 'UTC'
$$;
