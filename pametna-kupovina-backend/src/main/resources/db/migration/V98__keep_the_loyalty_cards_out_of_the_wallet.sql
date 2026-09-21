-- Gomila plastike u novčaniku, a sve što kasirka traži je broj sa kartice.
-- Kartica pripada nalogu, kao i spiskovi, pa prelazi na nov telefon zajedno
-- sa njima.
--
-- Broj se čuva kakav jeste: on se mora prikazati kao crtični kod na kasi, pa
-- ga nema smisla nepovratno skrivati. Nije tajna kao lozinka — najviše što
-- neko sa njim može jeste da skupi tuđe bodove.

CREATE TABLE app.loyalty_card (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,

    -- Kako je kupac zove („Super Kartica"), ne kako se lanac zvanično piše.
    name VARCHAR(120) NOT NULL,
    card_number VARCHAR(80) NOT NULL,
    -- Kako kasa čita kod: EAN_13, CODE_128, QR_CODE…
    barcode_format VARCHAR(20) NOT NULL DEFAULT 'CODE_128',
    retailer_id BIGINT,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_loyalty_card_account
        FOREIGN KEY (account_id) REFERENCES app.account (id)
            ON DELETE CASCADE,
    CONSTRAINT fk_loyalty_card_retailer
        FOREIGN KEY (retailer_id) REFERENCES app.retailer (id)
            ON DELETE SET NULL,
    -- Ista kartica dodata dvaput je i dalje jedna kartica.
    CONSTRAINT uq_loyalty_card_per_account
        UNIQUE (account_id, card_number),
    CONSTRAINT chk_loyalty_card_name
        CHECK (BTRIM(name) <> ''),
    CONSTRAINT chk_loyalty_card_number
        CHECK (BTRIM(card_number) <> '')
);

CREATE INDEX idx_loyalty_card_account
    ON app.loyalty_card (account_id, name);
