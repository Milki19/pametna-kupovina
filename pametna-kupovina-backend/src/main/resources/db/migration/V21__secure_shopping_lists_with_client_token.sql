ALTER TABLE app.shopping_list
    ADD COLUMN client_token_hash VARCHAR(64),
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;


UPDATE app.shopping_list
SET client_token_hash =
        MD5('legacy-shopping-list:' || id::TEXT)
        || MD5('legacy-shopping-list-owner:' || id::TEXT);


ALTER TABLE app.shopping_list
    ALTER COLUMN client_token_hash SET NOT NULL,

    ADD CONSTRAINT chk_shopping_list_client_token_hash
        CHECK (client_token_hash ~ '^[0-9a-f]{64}$');


CREATE INDEX idx_shopping_list_client_active_updated
    ON app.shopping_list (
        client_token_hash,
        updated_at DESC,
        id DESC
    )
    WHERE active = TRUE;
