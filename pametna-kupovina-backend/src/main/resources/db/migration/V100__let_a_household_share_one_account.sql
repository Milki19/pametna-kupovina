-- Domaćinstvo deli jedan nalog: drugi telefon ulazi u njega skenirajući kod
-- sa prvog. Čuva se samo otisak koda, i to kratko — kod važi nekoliko minuta
-- i troši se čim ga neko iskoristi.

ALTER TABLE app.account
    ADD COLUMN invite_code_hash VARCHAR(64) UNIQUE,
    ADD COLUMN invite_expires_at TIMESTAMPTZ;
