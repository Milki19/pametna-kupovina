-- One-time local cutover, NOT a schema migration. Run only after verifying
-- infra/backups/pre-retire-legacy-20260909.dump. Exact counts intentionally
-- prevent use on another database or after further imports.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '10s';
LOCK TABLE app.import_run IN SHARE MODE;
LOCK TABLE app.current_price_offer, app.price_observation IN SHARE ROW EXCLUSIVE MODE;
DO $$
DECLARE
    affected bigint;
BEGIN
    IF current_database() <> 'pametna_kupovina' THEN
        RAISE EXCEPTION 'Wrong database';
    END IF;
    IF EXISTS (SELECT 1 FROM app.import_run WHERE status='RUNNING') THEN
        RAISE EXCEPTION 'An import is still running';
    END IF;
    IF (SELECT count(*) FROM app.import_run
        WHERE id IN (26,27,32,37,40,41,42,43,44,45)
          AND status='SUCCEEDED' AND snapshot_date=DATE '2026-09-09') <> 10 THEN
        RAISE EXCEPTION 'Required new snapshots have not completed';
    END IF;
    IF (SELECT count(*) FROM app.current_price_offer WHERE price_date<DATE '2026-09-01') <> 231476
       OR (SELECT count(*) FROM app.price_observation WHERE price_date<DATE '2026-09-01') <> 269703 THEN
        RAISE EXCEPTION 'Legacy counts changed; inspect before running';
    END IF;

    DELETE FROM app.current_price_offer p USING app.retailer_product rp, app.retailer r
    WHERE p.retailer_product_id=rp.id AND rp.retailer_id=r.id
      AND r.code IN ('DIS','LIDL','EUROPROM','IDEA_RODA','MAXI','UNIVEREXPORT')
      AND p.price_date<DATE '2026-09-01';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 231476 THEN RAISE EXCEPTION 'Unexpected current-offer count: %', affected; END IF;
    RAISE NOTICE 'Retired % legacy current offers', affected;

    -- Historical fallback queries must not resurrect pre-cutover prices.
    DELETE FROM app.price_observation p USING app.retailer_product rp, app.retailer r
    WHERE p.retailer_product_id=rp.id AND rp.retailer_id=r.id
      AND r.code IN ('DIS','LIDL','EUROPROM','IDEA_RODA','MAXI','UNIVEREXPORT')
      AND p.price_date<DATE '2026-09-01';
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 269703 THEN RAISE EXCEPTION 'Unexpected history count: %', affected; END IF;
    RAISE NOTICE 'Retired % legacy observations; recoverable from backup', affected;

    -- DIS has no verified replacement feed; keep products and locations,
    -- but do not advertise old prices as currently usable.
    UPDATE app.retailer_data_source SET active=FALSE,updated_at=NOW()
    WHERE retailer_id=(SELECT id FROM app.retailer WHERE code='DIS')
      AND source_type='PRICE_CATALOG';
    UPDATE app.store SET pricing_eligible=FALSE,
        pricing_ineligibility_reason='Nema potvrđenog cenovnika posle prelaska 2026-09-01.',updated_at=NOW()
    WHERE retailer_id=(SELECT id FROM app.retailer WHERE code='DIS');
END $$;
COMMIT;
