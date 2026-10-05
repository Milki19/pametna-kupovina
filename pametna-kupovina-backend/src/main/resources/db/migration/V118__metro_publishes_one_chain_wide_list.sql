-- Until 02.10.2026 METRO published one price list per store (ST ZEMUN,
-- ST Vidikovac, ... nine lists, about 59,000 rows). Since 03.10 its file holds
-- a single chain-wide list "Metro CashCarry" with about 9,600 current prices,
-- plus monthly snapshots the import skips. Every import since 04.10 failed as
-- BELOW_MINIMUM against the 20,000 rows set in V64.
--
-- 7,000 accepts the chain's full list and still rejects a file cut short. The
-- first accepted run is held for a format review (1 list instead of 9); once
-- that is confirmed, the volume history starts again from it (V116).

UPDATE app.retailer_data_source source
SET expected_min_rows_saved = 7000,
    updated_at = NOW()
FROM app.retailer retailer
WHERE retailer.id = source.retailer_id
  AND retailer.code = 'METRO'
  AND source.code = 'PRIMARY_PRICE_CATALOG';
