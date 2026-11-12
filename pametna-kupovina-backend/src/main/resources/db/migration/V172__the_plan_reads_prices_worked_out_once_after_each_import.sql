-- Prvi plan za 100 stavki traje 3–4,5 s na serveru (2 jezgra), 1,0–1,3 s na
-- kopiji na Mac-u (merenje 12.11.2026, posle V170). Deo tog vremena plan
-- troši na ono što je isto ceo dan, dok se cene ne promene:
--   * da li proizvod ima staru cenu iz istorije koja još važi (has_loose_observation),
--     što je plan pitao za svaki par stavka × proizvod, a to su desetine
--     hiljada pretraga po istoriji cena;
--   * sve cene proizvoda iz najnovijih cenovnika, sa cenom na akciji za taj
--     dan (price_source u StoreShoppingOfferRepository).
-- Oba zavise samo od proizvoda i dana, ne od stavke, spiska ni radnje. Ovde
-- se računaju jednom posle uvoza (i posle ponoći za novi dan) i upisuju u
-- gotove tabele, istim upitom kao u planu, pa plan čita gotove redove.
--
-- Plan ih koristi samo kad su sveže: urađene za isti dan i posle poslednje
-- promene cena. Svaka izmena tabela cena (uvoz, ručna ispravka u bazi)
-- upisuje beleg, a tabele su urađene za tačno jedno stanje baze (snimak
-- transakcije). Kad posle tog snimka postoji ijedna potvrđena izmena, ili
-- tabele nisu urađene za traženi dan, ili gradnja padne, plan računa na
-- stari način, istim upitom kao do sada (PlanFacts u Javi).

-- Jedan red: za koji dan i za koje stanje baze su gotove tabele urađene.
CREATE TABLE app.plan_fact_build (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    as_of_date DATE NOT NULL,
    taken_snapshot pg_snapshot NOT NULL,
    built_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    took_ms INTEGER NOT NULL,
    loose_products INTEGER NOT NULL,
    prices INTEGER NOT NULL
);

-- Beleg da su se cene promenile: jedan red po transakciji koja je menjala
-- tabele cena, sa brojem te transakcije. Gradnja briše belege koje je
-- videla; ostali znače da su gotove tabele zastarele.
CREATE TABLE app.plan_fact_change (
    xact xid8 NOT NULL,
    table_name TEXT NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION app.note_plan_fact_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Once per transaction: an import writes row by row, and one mark is
    -- enough. The setting is the transaction's own and goes with it.
    IF current_setting('app.plan_fact_change_noted', TRUE)
           IS DISTINCT FROM pg_current_xact_id()::TEXT THEN
        PERFORM set_config('app.plan_fact_change_noted', pg_current_xact_id()::TEXT, TRUE);
        INSERT INTO app.plan_fact_change (xact, table_name)
        VALUES (pg_current_xact_id(), TG_TABLE_NAME);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER plan_fact_change
AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON app.current_price_offer
FOR EACH STATEMENT EXECUTE FUNCTION app.note_plan_fact_change();

CREATE TRIGGER plan_fact_change
AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON app.price_observation
FOR EACH STATEMENT EXECUTE FUNCTION app.note_plan_fact_change();

CREATE TRIGGER plan_fact_change
AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON app.price_list_snapshot
FOR EACH STATEMENT EXECUTE FUNCTION app.note_plan_fact_change();

-- A product moved to another chain would read another chain's lists.
CREATE TRIGGER plan_fact_change
AFTER UPDATE OF retailer_id OR DELETE OR TRUNCATE ON app.retailer_product
FOR EACH STATEMENT EXECUTE FUNCTION app.note_plan_fact_change();

-- Proizvodi čija stara cena iz istorije još može da bude cena u planu:
-- njen cenovnik nema današnju ponudu tog proizvoda (has_loose_observation).
-- Skoro nikad; zato samo oni.
CREATE TABLE app.plan_loose_product (
    retailer_product_id BIGINT PRIMARY KEY
);

-- Sve cene proizvoda iz najnovijih cenovnika, kao price_source u planu.
CREATE TABLE app.plan_price_source (
    retailer_product_id BIGINT NOT NULL,
    observed BOOLEAN NOT NULL,
    source_store_id BIGINT,
    scope_type VARCHAR(20),
    format_key TEXT,
    price_date DATE NOT NULL,
    regular_price NUMERIC,
    discounted_price NUMERIC,
    effective_price NUMERIC,
    price_scope TEXT,
    source_priority INTEGER NOT NULL,
    id BIGINT NOT NULL
);

CREATE INDEX idx_plan_price_source_product
    ON app.plan_price_source (retailer_product_id);

-- Builds both tables for one day. Run it in a REPEATABLE READ transaction:
-- every statement then reads one state of the database, the one it records.
CREATE FUNCTION app.refresh_plan_facts(build_date DATE)
RETURNS app.plan_fact_build
LANGUAGE plpgsql
AS $$
DECLARE
    started TIMESTAMPTZ := clock_timestamp();
    taken pg_snapshot;
    loose_count INTEGER;
    price_count INTEGER;
    built app.plan_fact_build;
BEGIN
    IF current_setting('transaction_isolation') <> 'repeatable read' THEN
        RAISE EXCEPTION 'app.refresh_plan_facts needs a REPEATABLE READ transaction';
    END IF;
    taken := pg_current_snapshot();

    -- The same test as has_loose_observation in the plan, for every product.
    DELETE FROM app.plan_loose_product;
    INSERT INTO app.plan_loose_product (retailer_product_id)
    SELECT DISTINCT loose.retailer_product_id
    FROM app.price_observation AS loose
    JOIN app.retailer_product AS product
      ON product.id = loose.retailer_product_id
    CROSS JOIN LATERAL (
        SELECT CASE
                   WHEN loose.store_id IS NOT NULL
                       THEN 'STORE:' || loose.store_id::TEXT
                   WHEN NULLIF(BTRIM(
                       loose.retailer_format_name
                   ), '') IS NOT NULL
                       THEN 'STORE_FORMAT:' ||
                            LOWER(BTRIM(
                                loose.retailer_format_name
                            ))
                   ELSE 'RETAILER'
               END AS scope_key
    ) AS loose_scope
    LEFT JOIN app.price_list_snapshot AS loose_list
      ON loose_list.retailer_id = product.retailer_id
     AND loose_list.scope_key = loose_scope.scope_key
    WHERE loose.price_date <= build_date
      AND COALESCE(
          CASE
              WHEN loose_list.latest_price_date > build_date
                  THEN TRUE
              ELSE loose_list.latest_price_date = loose.price_date
                   AND NOT loose_list.retired
          END,
          TRUE
      )
      AND NOT EXISTS (
          SELECT 1
          FROM app.current_price_offer
              AS loose_current
          WHERE loose_current.retailer_product_id =
                loose.retailer_product_id
            AND loose_current.price_date <=
                build_date
            AND loose_current.scope_key =
                loose_scope.scope_key
      );
    GET DIAGNOSTICS loose_count = ROW_COUNT;

    -- The same rows as price_source in the plan, for every product.
    DELETE FROM app.plan_price_source;
    INSERT INTO app.plan_price_source (
        retailer_product_id, observed, source_store_id, scope_type, format_key,
        price_date, regular_price, discounted_price, effective_price,
        price_scope, source_priority, id
    )
    SELECT source.retailer_product_id,
           source.observed,
           source.source_store_id,
           source.scope_type,
           source.format_key,
           source.price_date,
           source.regular_price,
           source.discounted_price,
           source.effective_price,
           source.price_scope,
           source.source_priority,
           source.id
    FROM (
        SELECT current_offer.retailer_product_id,
               product.retailer_id,
               FALSE AS observed,
               current_offer.store_id AS source_store_id,
               current_offer.scope_type,
               LOWER(BTRIM(
                   current_offer.retailer_format_name
               )) AS format_key,
               current_offer.price_date,
               current_offer.regular_price,
               current_offer.discounted_price,
               app.effective_price(
                   current_offer.regular_price,
                   current_offer.discounted_price,
                   current_offer.discount_start,
                   current_offer.discount_end,
                   build_date
               ) AS effective_price,
               current_offer.scope_type AS price_scope,
               0 AS source_priority,
               current_offer.id,
               current_offer.scope_key
        FROM app.current_price_offer AS current_offer
        JOIN app.retailer_product AS product
          ON product.id = current_offer.retailer_product_id
        WHERE current_offer.price_date <= build_date
        UNION ALL
        SELECT observation.retailer_product_id,
               product.retailer_id,
               TRUE AS observed,
               observation.store_id AS source_store_id,
               NULL AS scope_type,
               LOWER(NULLIF(BTRIM(
                   observation.retailer_format_name
               ), '')) AS format_key,
               observation.price_date,
               observation.regular_price,
               observation.discounted_price,
               app.effective_price(
                   observation.regular_price,
                   observation.discounted_price,
                   observation.discount_start,
                   observation.discount_end,
                   build_date
               ) AS effective_price,
               CASE
                   WHEN observation.store_id IS NOT NULL
                       THEN 'STORE'
                   WHEN NULLIF(BTRIM(
                       observation.retailer_format_name
                   ), '') IS NOT NULL
                       THEN 'STORE_FORMAT'
                   ELSE 'RETAILER'
               END AS price_scope,
               1 AS source_priority,
               observation.id,
               observation_scope.scope_key
        FROM app.plan_loose_product AS loose
        JOIN app.price_observation AS observation
          ON observation.retailer_product_id = loose.retailer_product_id
        JOIN app.retailer_product AS product
          ON product.id = observation.retailer_product_id
        CROSS JOIN LATERAL (
            SELECT CASE
                       WHEN observation.store_id IS NOT NULL
                           THEN 'STORE:' || observation.store_id::TEXT
                       WHEN NULLIF(BTRIM(observation.retailer_format_name), '') IS NOT NULL
                           THEN 'STORE_FORMAT:' || LOWER(BTRIM(observation.retailer_format_name))
                       ELSE 'RETAILER'
                   END AS scope_key
        ) AS observation_scope
        WHERE observation.price_date <= build_date
          AND NOT EXISTS (
              SELECT 1
              FROM app.current_price_offer
                  AS available_current
              WHERE available_current.retailer_product_id =
                    observation.retailer_product_id
                AND available_current.price_date <=
                    build_date
                AND available_current.scope_key =
                    observation_scope.scope_key
          )
    ) AS source
    LEFT JOIN app.price_list_snapshot AS snapshot
      ON snapshot.retailer_id = source.retailer_id
     AND snapshot.scope_key = source.scope_key
    WHERE source.effective_price > 0
      AND COALESCE(
          CASE
              WHEN snapshot.latest_price_date > build_date
                  THEN TRUE
              ELSE snapshot.latest_price_date = source.price_date
                   AND NOT snapshot.retired
          END,
          TRUE
      );
    GET DIAGNOSTICS price_count = ROW_COUNT;

    -- What this build saw is in the tables now; what it did not see stays
    -- and keeps them stale until the next build.
    DELETE FROM app.plan_fact_change AS change
    WHERE pg_visible_in_snapshot(change.xact, taken);

    INSERT INTO app.plan_fact_build AS build (
        id, as_of_date, taken_snapshot, built_at, took_ms, loose_products, prices
    )
    VALUES (
        1, build_date, taken, clock_timestamp(),
        (EXTRACT(EPOCH FROM clock_timestamp() - started) * 1000)::INTEGER,
        loose_count, price_count
    )
    ON CONFLICT (id) DO UPDATE SET
        as_of_date = EXCLUDED.as_of_date,
        taken_snapshot = EXCLUDED.taken_snapshot,
        built_at = EXCLUDED.built_at,
        took_ms = EXCLUDED.took_ms,
        loose_products = EXCLUDED.loose_products,
        prices = EXCLUDED.prices
    RETURNING build.* INTO built;

    RETURN built;
END;
$$;

-- The day the tables are for, while nothing changed since they were built;
-- nothing otherwise.
CREATE FUNCTION app.fresh_plan_facts_date()
RETURNS DATE
LANGUAGE sql
STABLE
AS $$
    SELECT build.as_of_date
    FROM app.plan_fact_build AS build
    WHERE NOT EXISTS (
        SELECT 1
        FROM app.plan_fact_change AS change
        WHERE NOT pg_visible_in_snapshot(change.xact, build.taken_snapshot)
    )
$$;
