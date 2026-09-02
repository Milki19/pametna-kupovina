-- Preporuke čitaju mali presek aktuelnih ponuda, dok price_observation
-- postaje istorija stvarnih promena. Time dnevni import više ne mora da
-- uvećava istorijsku tabelu kada se cena nije promenila.
CREATE TABLE app.current_price_offer (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retailer_product_id BIGINT NOT NULL,
    import_run_id BIGINT NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    retailer_format_name VARCHAR(200),
    store_id BIGINT,
    scope_key TEXT GENERATED ALWAYS AS (
        CASE
            WHEN store_id IS NOT NULL
                THEN 'STORE:' || store_id::TEXT
            WHEN NULLIF(BTRIM(retailer_format_name), '') IS NOT NULL
                THEN 'STORE_FORMAT:' || LOWER(BTRIM(retailer_format_name))
            ELSE 'RETAILER'
        END
    ) STORED,
    price_date DATE NOT NULL,
    first_seen_date DATE NOT NULL,
    last_seen_date DATE NOT NULL,
    regular_price NUMERIC(12, 2),
    unit_price NUMERIC(14, 4),
    discounted_price NUMERIC(12, 2),
    discount_start DATE,
    discount_end DATE,
    vat_rate NUMERIC(5, 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_current_price_offer_retailer_product
        FOREIGN KEY (retailer_product_id)
            REFERENCES app.retailer_product (id),

    CONSTRAINT fk_current_price_offer_import_run
        FOREIGN KEY (import_run_id)
            REFERENCES app.import_run (id),

    CONSTRAINT fk_current_price_offer_store
        FOREIGN KEY (store_id)
            REFERENCES app.store (id),

    CONSTRAINT uq_current_price_offer_scope
        UNIQUE (retailer_product_id, scope_type, scope_key),

    CONSTRAINT chk_current_price_offer_scope_type
        CHECK (scope_type IN ('STORE', 'STORE_FORMAT', 'RETAILER')),

    CONSTRAINT chk_current_price_offer_scope_values
        CHECK (
            (scope_type = 'STORE'
                AND store_id IS NOT NULL)
            OR
            (scope_type = 'STORE_FORMAT'
                AND store_id IS NULL
                AND NULLIF(BTRIM(retailer_format_name), '') IS NOT NULL)
            OR
            (scope_type = 'RETAILER'
                AND store_id IS NULL
                AND NULLIF(BTRIM(retailer_format_name), '') IS NULL)
        ),

    CONSTRAINT chk_current_price_offer_has_price
        CHECK (regular_price IS NOT NULL OR discounted_price IS NOT NULL),

    CONSTRAINT chk_current_price_offer_prices
        CHECK (
            (regular_price IS NULL OR regular_price >= 0)
            AND (unit_price IS NULL OR unit_price >= 0)
            AND (discounted_price IS NULL OR discounted_price >= 0)
        ),

    CONSTRAINT chk_current_price_offer_discount_dates
        CHECK (
            discount_start IS NULL
            OR discount_end IS NULL
            OR discount_end >= discount_start
        ),

    CONSTRAINT chk_current_price_offer_vat_rate
        CHECK (vat_rate IS NULL OR (vat_rate >= 0 AND vat_rate <= 100)),

    CONSTRAINT chk_current_price_offer_seen_dates
        CHECK (
            first_seen_date <= price_date
            AND price_date <= last_seen_date
        ),

    CONSTRAINT chk_current_price_offer_timestamps
        CHECK (updated_at >= created_at)
);


-- Backfill bira poslednju poznatu ponudu za svaki proizvod i cenovni opseg.
INSERT INTO app.current_price_offer (
    retailer_product_id,
    import_run_id,
    scope_type,
    retailer_format_name,
    store_id,
    price_date,
    first_seen_date,
    last_seen_date,
    regular_price,
    unit_price,
    discounted_price,
    discount_start,
    discount_end,
    vat_rate,
    created_at,
    updated_at
)
SELECT DISTINCT ON (
           observation.retailer_product_id,
           CASE
               WHEN observation.store_id IS NOT NULL THEN 'STORE'
               WHEN NULLIF(BTRIM(observation.retailer_format_name), '')
                       IS NOT NULL THEN 'STORE_FORMAT'
               ELSE 'RETAILER'
           END,
           CASE
               WHEN observation.store_id IS NOT NULL
                   THEN 'STORE:' || observation.store_id::TEXT
               WHEN NULLIF(BTRIM(observation.retailer_format_name), '')
                       IS NOT NULL
                   THEN 'STORE_FORMAT:' || LOWER(BTRIM(
                       observation.retailer_format_name
                   ))
               ELSE 'RETAILER'
           END
       )
       observation.retailer_product_id,
       observation.import_run_id,
       CASE
           WHEN observation.store_id IS NOT NULL THEN 'STORE'
           WHEN NULLIF(BTRIM(observation.retailer_format_name), '')
                   IS NOT NULL THEN 'STORE_FORMAT'
           ELSE 'RETAILER'
       END,
       NULLIF(BTRIM(observation.retailer_format_name), ''),
       observation.store_id,
       observation.price_date,
       observation.price_date,
       observation.price_date,
       observation.regular_price,
       observation.unit_price,
       observation.discounted_price,
       observation.discount_start,
       observation.discount_end,
       observation.vat_rate,
       observation.created_at,
       NOW()
FROM app.price_observation AS observation
ORDER BY observation.retailer_product_id,
         CASE
             WHEN observation.store_id IS NOT NULL THEN 'STORE'
             WHEN NULLIF(BTRIM(observation.retailer_format_name), '')
                     IS NOT NULL THEN 'STORE_FORMAT'
             ELSE 'RETAILER'
         END,
         CASE
             WHEN observation.store_id IS NOT NULL
                 THEN 'STORE:' || observation.store_id::TEXT
             WHEN NULLIF(BTRIM(observation.retailer_format_name), '')
                     IS NOT NULL
                 THEN 'STORE_FORMAT:' || LOWER(BTRIM(
                     observation.retailer_format_name
                 ))
             ELSE 'RETAILER'
         END,
         observation.price_date DESC,
         observation.id DESC;


-- U postojećim podacima zadržavamo prvi red svake nepromenjene serije.
-- Ako se cena promeni pa se kasnije vrati na staru vrednost, novi početak
-- serije ostaje u istoriji.
WITH ordered_history AS (
    SELECT observation.id,
           observation.regular_price,
           observation.unit_price,
           observation.discounted_price,
           observation.discount_start,
           observation.discount_end,
           observation.vat_rate,
           LAG(observation.regular_price) OVER scope_order AS previous_regular,
           LAG(observation.unit_price) OVER scope_order AS previous_unit,
           LAG(observation.discounted_price) OVER scope_order AS previous_discounted,
           LAG(observation.discount_start) OVER scope_order AS previous_discount_start,
           LAG(observation.discount_end) OVER scope_order AS previous_discount_end,
           LAG(observation.vat_rate) OVER scope_order AS previous_vat,
           ROW_NUMBER() OVER scope_order AS row_number
    FROM app.price_observation AS observation
    WINDOW scope_order AS (
        PARTITION BY observation.retailer_product_id,
                     observation.store_id,
                     LOWER(BTRIM(COALESCE(
                         observation.retailer_format_name,
                         ''
                     )))
        ORDER BY observation.price_date ASC,
                 observation.id ASC
    )
), redundant AS (
    SELECT id
    FROM ordered_history
    WHERE row_number > 1
      AND regular_price IS NOT DISTINCT FROM previous_regular
      AND unit_price IS NOT DISTINCT FROM previous_unit
      AND discounted_price IS NOT DISTINCT FROM previous_discounted
      AND discount_start IS NOT DISTINCT FROM previous_discount_start
      AND discount_end IS NOT DISTINCT FROM previous_discount_end
      AND vat_rate IS NOT DISTINCT FROM previous_vat
)
DELETE FROM app.price_observation AS observation
USING redundant
WHERE observation.id = redundant.id;


CREATE INDEX idx_current_price_offer_product
    ON app.current_price_offer (
        retailer_product_id,
        price_date DESC
    );

CREATE INDEX idx_current_price_offer_store
    ON app.current_price_offer (
        store_id,
        retailer_product_id
    )
    WHERE store_id IS NOT NULL;

CREATE INDEX idx_current_price_offer_format
    ON app.current_price_offer (
        LOWER(BTRIM(retailer_format_name)),
        retailer_product_id
    )
    WHERE scope_type = 'STORE_FORMAT';
