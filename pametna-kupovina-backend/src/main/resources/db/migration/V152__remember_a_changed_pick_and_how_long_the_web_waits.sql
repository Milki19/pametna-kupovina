-- N6, merenje kvaliteta van primera iz izveštaja.
--
-- 1. „Izaberi drugi proizvod“: kad kupac u koraku 1 (ili na spisku) stavci
--    da drugi proizvod od onog koji je aplikacija našla, pamti se linija,
--    šta je bilo nađeno i šta je izabrano. Bez naloga i uređaja: dovoljno je
--    znati koja linija se loše čita, ne ko ju je napisao. Čuva se 180 dana.
CREATE TABLE app.product_choice_change (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    market_id BIGINT REFERENCES app.market (id) ON DELETE SET NULL,
    line_text VARCHAR(1000) NOT NULL,
    line_normalized VARCHAR(1000) NOT NULL,
    previous_rule VARCHAR(30) NOT NULL,
    previous_status VARCHAR(30) NOT NULL,
    previous_canonical_product_id BIGINT REFERENCES app.canonical_product (id) ON DELETE SET NULL,
    previous_product_family_id BIGINT REFERENCES app.product_family (id) ON DELETE SET NULL,
    previous_shopping_intent_id BIGINT REFERENCES app.shopping_intent (id) ON DELETE SET NULL,
    previous_score NUMERIC(5, 4),
    chosen_canonical_product_id BIGINT REFERENCES app.canonical_product (id) ON DELETE SET NULL,
    chosen_product_family_id BIGINT REFERENCES app.product_family (id) ON DELETE SET NULL,

    CONSTRAINT chk_product_choice_change_line CHECK (BTRIM(line_text) <> '')
);

CREATE INDEX idx_product_choice_change_created
    ON app.product_choice_change (created_at);

-- 2. Merenje u pregledaču: koliko web verzija čeka na otvaranje, korak 1,
--    preporuku i pretragu, kako to vidi kupac (sa mrežom). Šalje se samo
--    našem serveru, bez naloga, adrese i sadržaja spiska. Čuva se 90 dana.
CREATE TABLE app.web_timing (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    app_version VARCHAR(20) NOT NULL,
    metric VARCHAR(20) NOT NULL,
    duration_ms INTEGER NOT NULL,

    CONSTRAINT chk_web_timing_metric
        CHECK (metric IN ('prvi-odgovor', 'otvaranje', 'korak1', 'preporuka', 'pretraga')),
    CONSTRAINT chk_web_timing_duration
        CHECK (duration_ms BETWEEN 0 AND 600000)
);

CREATE INDEX idx_web_timing_created
    ON app.web_timing (created_at);
