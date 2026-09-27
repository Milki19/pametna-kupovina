-- A market is a country the app runs in: its currency, how its numbers and
-- dates read, the language its catalogue is written in, the clock its price
-- lists follow and what a kilometre or an hour costs there. Until now all of
-- that was Serbia, written into the code. Serbia becomes the first market and
-- everything that exists today belongs to it, so nothing changes for anyone.

CREATE TABLE app.market (
    id SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- ISO 3166-1 alpha-2.
    code CHAR(2) NOT NULL,
    name VARCHAR(100) NOT NULL,
    -- ISO 4217, and how many decimals an amount in it has.
    currency_code CHAR(3) NOT NULL,
    currency_minor_units SMALLINT NOT NULL DEFAULT 2,
    -- BCP 47: how amounts and dates are written (1.086,92 or 1,086.92).
    locale VARCHAR(35) NOT NULL,
    -- BCP 47: the language the market's catalogue and aliases are in.
    default_language VARCHAR(35) NOT NULL,
    -- IANA zone: when "today" starts for prices, receipts and imports.
    time_zone VARCHAR(64) NOT NULL,
    -- What getting to a shop costs, in the market's currency.
    travel_cost_per_km NUMERIC(12, 2) NOT NULL,
    value_per_hour NUMERIC(12, 2) NOT NULL,
    cost_per_stop NUMERIC(12, 2) NOT NULL,
    -- The market a new account, chain or dataset belongs to unless told.
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_market_code UNIQUE (code),
    CONSTRAINT chk_market_code CHECK (code ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_market_name CHECK (BTRIM(name) <> ''),
    CONSTRAINT chk_market_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_market_minor_units
        CHECK (currency_minor_units BETWEEN 0 AND 4),
    CONSTRAINT chk_market_locale CHECK (BTRIM(locale) <> ''),
    CONSTRAINT chk_market_language CHECK (BTRIM(default_language) <> ''),
    -- An unknown zone makes AT TIME ZONE fail, so a typo never gets in.
    CONSTRAINT chk_market_time_zone
        CHECK ((TIMESTAMPTZ '2000-01-01 00:00:00+00' AT TIME ZONE time_zone) IS NOT NULL),
    CONSTRAINT chk_market_travel_costs
        CHECK (travel_cost_per_km >= 0 AND value_per_hour >= 0 AND cost_per_stop >= 0),
    -- The default market is the one everything falls back to.
    CONSTRAINT chk_market_default_active CHECK (NOT is_default OR active)
);

CREATE UNIQUE INDEX uq_market_single_default
    ON app.market (is_default)
    WHERE is_default;

INSERT INTO app.market (
    code, name, currency_code, currency_minor_units, locale,
    default_language, time_zone, travel_cost_per_km, value_per_hour,
    cost_per_stop, is_default
)
VALUES (
    'RS', 'Srbija', 'RSD', 2, 'sr-Latn-RS',
    'sr-Latn', 'Europe/Belgrade', 20.00, 400.00,
    80.00, TRUE
);

CREATE FUNCTION app.default_market_id()
RETURNS SMALLINT
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT id FROM app.market WHERE is_default
$$;

CREATE FUNCTION app.default_language()
RETURNS VARCHAR
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT default_language FROM app.market WHERE is_default
$$;

-- Today's date where the market is, whatever zone the server runs in.
CREATE FUNCTION app.market_today(target_market_id INTEGER)
RETURNS DATE
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT (NOW() AT TIME ZONE time_zone)::DATE
    FROM app.market
    WHERE id = target_market_id
$$;

-- A chain sells in one market. Lidl in Serbia and Lidl in Croatia are two
-- chains with two codes, so a chain's code stays unique on its own.
ALTER TABLE app.retailer
    ADD COLUMN market_id SMALLINT NOT NULL DEFAULT app.default_market_id()
        CONSTRAINT fk_retailer_market REFERENCES app.market (id);

CREATE INDEX idx_retailer_market ON app.retailer (market_id);

-- An account shops in one market; a household shares it.
ALTER TABLE app.account
    ADD COLUMN market_id SMALLINT NOT NULL DEFAULT app.default_market_id()
        CONSTRAINT fk_account_market REFERENCES app.market (id);

-- A government open-data portal belongs to one country.
ALTER TABLE app.government_dataset_candidate
    ADD COLUMN market_id SMALLINT NOT NULL DEFAULT app.default_market_id()
        CONSTRAINT fk_government_dataset_candidate_market REFERENCES app.market (id);

-- A receipt keeps the market and the currency it was paid in, so a market
-- that changes its currency does not rewrite what people already spent.
ALTER TABLE app.receipt
    ADD COLUMN market_id SMALLINT
        CONSTRAINT fk_receipt_market REFERENCES app.market (id),
    ADD COLUMN currency_code CHAR(3);

UPDATE app.receipt AS receipt
   SET market_id = account.market_id,
       currency_code = market.currency_code
  FROM app.account AS account
  JOIN app.market AS market ON market.id = account.market_id
 WHERE account.id = receipt.account_id;

ALTER TABLE app.receipt
    ALTER COLUMN market_id SET NOT NULL,
    ALTER COLUMN currency_code SET NOT NULL,
    ADD CONSTRAINT chk_receipt_currency CHECK (currency_code ~ '^[A-Z]{3}$');

-- Whoever writes a receipt writes it in its account's market, unless it says
-- otherwise.
CREATE FUNCTION app.receipt_takes_account_market()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.market_id IS NULL THEN
        SELECT account.market_id
          INTO NEW.market_id
          FROM app.account AS account
         WHERE account.id = NEW.account_id;
    END IF;

    IF NEW.currency_code IS NULL THEN
        SELECT market.currency_code
          INTO NEW.currency_code
          FROM app.market AS market
         WHERE market.id = NEW.market_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_receipt_takes_account_market
    BEFORE INSERT ON app.receipt
    FOR EACH ROW
    EXECUTE FUNCTION app.receipt_takes_account_market();

-- What a product typically costs is a price in one currency, so it is kept
-- per market.
ALTER TABLE app.product_family_typical_price
    ADD COLUMN market_id SMALLINT NOT NULL DEFAULT app.default_market_id()
        CONSTRAINT fk_product_family_typical_price_market REFERENCES app.market (id);

ALTER TABLE app.product_family_typical_price
    ALTER COLUMN market_id DROP DEFAULT,
    DROP CONSTRAINT product_family_typical_price_pkey,
    ADD CONSTRAINT product_family_typical_price_pkey
        PRIMARY KEY (product_family_id, market_id);

CREATE OR REPLACE FUNCTION app.refresh_typical_prices()
RETURNS void
LANGUAGE plpgsql
AS $function$
BEGIN
    DELETE FROM app.product_family_typical_price;

    INSERT INTO app.product_family_typical_price (
        product_family_id,
        market_id,
        typical_price,
        retailer_count
    )
    SELECT chain_price.product_family_id,
           chain_price.market_id,
           ROUND(
               PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY chain_price.price)
                   ::NUMERIC,
               2
           ),
           COUNT(*)::INTEGER
    FROM (
        -- The chain's own median: one store with a clearance price, or a
        -- chain with many stores, does not decide what is typical.
        SELECT product.product_family_id,
               retailer.market_id,
               product.retailer_id,
               PERCENTILE_CONT(0.5) WITHIN GROUP (
                   ORDER BY COALESCE(
                       NULLIF(offer.regular_price, 0),
                       offer.discounted_price
                   )
               ) AS price
        FROM app.current_price_offer AS offer
        JOIN app.retailer_product AS product
          ON product.id = offer.retailer_product_id
        JOIN app.retailer AS retailer
          ON retailer.id = product.retailer_id
        JOIN app.market AS market
          ON market.id = retailer.market_id
        WHERE product.product_family_id IS NOT NULL
          AND COALESCE(NULLIF(offer.regular_price, 0), offer.discounted_price) > 0
          AND app.in_latest_price_list(
              product.retailer_id,
              offer.scope_key,
              offer.price_date,
              (NOW() AT TIME ZONE market.time_zone)::DATE
          )
          -- A case of the product is priced for all its pieces.
          AND product.package_count = app.family_base_package_count(product.product_family_id)
        GROUP BY product.product_family_id, retailer.market_id, product.retailer_id
    ) AS chain_price
    GROUP BY chain_price.product_family_id, chain_price.market_id
    HAVING COUNT(*) >= 3;
END;
$function$;

-- The words the catalogue recognises are words of one language. Everything
-- written so far is in the default market's language.
ALTER TABLE app.brand_alias
    ADD COLUMN language_code VARCHAR(35) NOT NULL DEFAULT app.default_language();
ALTER TABLE app.product_category_alias
    ADD COLUMN language_code VARCHAR(35) NOT NULL DEFAULT app.default_language();
ALTER TABLE app.product_category_rule
    ADD COLUMN language_code VARCHAR(35) NOT NULL DEFAULT app.default_language();
ALTER TABLE app.product_type_alias
    ADD COLUMN language_code VARCHAR(35) NOT NULL DEFAULT app.default_language();
ALTER TABLE app.product_type_rule
    ADD COLUMN language_code VARCHAR(35) NOT NULL DEFAULT app.default_language();
ALTER TABLE app.shopping_intent_alias
    ADD COLUMN language_code VARCHAR(35) NOT NULL DEFAULT app.default_language();
ALTER TABLE app.product_word_spelling
    ADD COLUMN language_code VARCHAR(35) NOT NULL DEFAULT app.default_language();
