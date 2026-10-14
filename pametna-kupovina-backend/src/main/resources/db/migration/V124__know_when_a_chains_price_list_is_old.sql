-- A price list older than its chain usually lets one get was shown as an
-- equal of today's lists: on 13.10. C&C Plus Kula's list of 07.10. was the
-- "Najbolja cena" for some products. A fixed "older than 3 days" would mark
-- METRO and Veropoulos every day, since they publish days late. So each chain
-- gets its own limit, read from how old its lists usually are by the time the
-- next one arrives.
--
-- Also: names came in with runs of odd spaces (no-break, thin) and shops with
-- the chain's internal number in front ("213 Orasac"). Import now cleans them
-- (PriceImportService, RetailerLocationImportService); the rows already here
-- are cleaned once below with the same rules.

CREATE TABLE app.price_list_rhythm (
    retailer_id BIGINT PRIMARY KEY REFERENCES app.retailer (id) ON DELETE CASCADE,
    -- How old the chain's list usually is (90th percentile) when the next one
    -- arrives, in the chain's market days.
    usual_age_days INTEGER NOT NULL,
    -- A list older than this on a given day is old.
    stale_after_days INTEGER NOT NULL,
    publications INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Read from the imports of the last 60 days: for each list the chain
-- published, the day the next one first arrived. A daily chain on time lands
-- at 1 day and gets 2; METRO, a day's list three days late, lands at 4 and
-- gets 5. A chain without four replaced lists yet keeps the default
-- (app.price_is_stale).
CREATE OR REPLACE FUNCTION app.refresh_price_list_rhythm()
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM app.price_list_rhythm;

    INSERT INTO app.price_list_rhythm (
        retailer_id,
        usual_age_days,
        stale_after_days,
        publications
    )
    WITH arrival AS (
        SELECT run.retailer_id,
               run.snapshot_date,
               MIN((run.started_at AT TIME ZONE market.time_zone)::DATE)
                   AS arrived_on
        FROM app.import_run AS run
        JOIN app.retailer AS chain
          ON chain.id = run.retailer_id
        JOIN app.market AS market
          ON market.id = chain.market_id
        WHERE run.status LIKE 'SUCCEEDED%'
          AND run.snapshot_date IS NOT NULL
          AND run.started_at >= NOW() - INTERVAL '60 days'
        GROUP BY run.retailer_id, run.snapshot_date
    ), replaced AS (
        SELECT retailer_id,
               LEAD(arrived_on) OVER (
                   PARTITION BY retailer_id
                   ORDER BY snapshot_date
               ) - snapshot_date AS age_days
        FROM arrival
    ), usual AS (
        SELECT retailer_id,
               CEIL(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY age_days))
                   ::INTEGER AS usual_age_days,
               COUNT(*)::INTEGER AS publications
        FROM replaced
        WHERE age_days >= 0
        GROUP BY retailer_id
        HAVING COUNT(*) >= 4
    )
    SELECT retailer_id,
           usual_age_days,
           -- One list more of slack, two days at least; a month at most: a
           -- chain gone quiet is not a slow chain.
           LEAST(GREATEST(usual_age_days + 1, 2), 31),
           publications
    FROM usual;
END;
$$;

-- True when a chain's price of price_date is older on as_of_date than the
-- chain's lists usually get. A chain with no rhythm yet allows 3 days.
CREATE OR REPLACE FUNCTION app.price_is_stale(
    target_retailer_id BIGINT,
    target_price_date DATE,
    as_of_date DATE
)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT as_of_date - target_price_date > COALESCE((
        SELECT rhythm.stale_after_days
        FROM app.price_list_rhythm AS rhythm
        WHERE rhythm.retailer_id = target_retailer_id
    ), 3)
$$;

SELECT app.refresh_price_list_rhythm();

-- Every kind of space (no-break, thin, zero-width) read as one plain space,
-- and none at either end.
CREATE OR REPLACE FUNCTION app.clean_spaces(value TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT BTRIM(REGEXP_REPLACE(
        value,
        '[\s   -​  　﻿]+',
        ' ',
        'g'
    ))
$$;

-- A shop's name without the chain's internal shop number in front:
-- "213 Orasac" -> "Orasac". A name that is only a number stays.
CREATE OR REPLACE FUNCTION app.clean_store_name(store_name TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(
        NULLIF(REGEXP_REPLACE(
            app.clean_spaces(store_name),
            '^[0-9]{2,}\s*[-.:]?\s+',
            ''
        ), ''),
        app.clean_spaces(store_name)
    )
$$;

-- A product's name without a stock code in front, as V81 already cut it for
-- display: "369680 Dove gel 700 ml" -> "Dove gel 700 ml".
CREATE OR REPLACE FUNCTION app.clean_retailer_product_name(product_name TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT COALESCE(
        NULLIF(REGEXP_REPLACE(
            app.clean_spaces(product_name),
            '^[0-9]{5,}\s+',
            ''
        ), ''),
        app.clean_spaces(product_name)
    )
$$;

UPDATE app.retailer_product
SET name = app.clean_retailer_product_name(name)
WHERE name IS DISTINCT FROM app.clean_retailer_product_name(name);

UPDATE app.canonical_product
SET name = app.clean_retailer_product_name(name)
WHERE name IS DISTINCT FROM app.clean_retailer_product_name(name);

UPDATE app.product_family
SET display_name = app.clean_spaces(display_name)
WHERE display_name IS DISTINCT FROM app.clean_spaces(display_name);

UPDATE app.product_family
SET composed_name = app.clean_spaces(composed_name)
WHERE composed_name IS DISTINCT FROM app.clean_spaces(composed_name);

UPDATE app.store
SET name = app.clean_store_name(name)
WHERE name IS DISTINCT FROM app.clean_store_name(name);
