-- Do sada je spisak pripadao uređaju: otisak slučajnog broja iz aplikacije
-- (V21) bio je jedini vlasnik. Ko promeni telefon, izgubio je spiskove.
--
-- Uređaj sada pripada nalogu, a nalog spiskovima. Dok se korisnik ne prijavi,
-- nalog nema nijedan lični podatak — to je isti slučajan broj, samo sa jednim
-- slojem između, koji sutra dozvoljava da se dva uređaja nađu pod istim
-- nalogom.

CREATE TABLE app.account (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE app.account_device (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    client_token_hash VARCHAR(64) NOT NULL UNIQUE,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_account_device_account
        FOREIGN KEY (account_id) REFERENCES app.account (id)
            ON DELETE CASCADE,
    CONSTRAINT chk_account_device_token_hash
        CHECK (client_token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_account_device_account
    ON app.account_device (account_id);

-- Prijava kači identitet na postojeći nalog. Čuva se samo ono po čemu nas
-- Google prepozna istog korisnika (`sub`), ne i ime ni slika.
CREATE TABLE app.account_identity (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_account_identity_account
        FOREIGN KEY (account_id) REFERENCES app.account (id)
            ON DELETE CASCADE,
    CONSTRAINT uq_account_identity UNIQUE (provider, subject),
    CONSTRAINT chk_account_identity_provider
        CHECK (provider IN ('GOOGLE')),
    CONSTRAINT chk_account_identity_subject
        CHECK (BTRIM(subject) <> '')
);

CREATE INDEX idx_account_identity_account
    ON app.account_identity (account_id);


-- Svaki uređaj koji već ima spisak dobija svoj nalog; ništa se ne gubi.
-- Otisak se za to vreme drži uz nalog, samo da bi se red znao spojiti sa
-- svojim uređajem, pa se odmah briše.
ALTER TABLE app.account
    ADD COLUMN legacy_token_hash VARCHAR(64);

INSERT INTO app.account (legacy_token_hash)
SELECT DISTINCT client_token_hash
FROM app.shopping_list;

INSERT INTO app.account_device (account_id, client_token_hash)
SELECT id, legacy_token_hash
FROM app.account
WHERE legacy_token_hash IS NOT NULL;


ALTER TABLE app.shopping_list
    ADD COLUMN account_id BIGINT;

UPDATE app.shopping_list AS list
SET account_id = account.id
FROM app.account
WHERE account.legacy_token_hash = list.client_token_hash;

ALTER TABLE app.account
    DROP COLUMN legacy_token_hash;

ALTER TABLE app.shopping_list
    ALTER COLUMN account_id SET NOT NULL,
    ADD CONSTRAINT fk_shopping_list_account
        FOREIGN KEY (account_id) REFERENCES app.account (id)
            ON DELETE CASCADE;

CREATE INDEX idx_shopping_list_account_active_updated
    ON app.shopping_list (account_id, updated_at DESC, id DESC)
    WHERE active = TRUE;

-- Veza uređaj → nalog sada živi u `account_device`; na spisku bi ostala kao
-- druga istina koja ume da se raziđe čim se dva uređaja spoje pod jedan nalog.
DROP INDEX IF EXISTS app.idx_shopping_list_client_active_updated;

ALTER TABLE app.shopping_list
    DROP CONSTRAINT IF EXISTS chk_shopping_list_client_token_hash,
    DROP COLUMN client_token_hash;
