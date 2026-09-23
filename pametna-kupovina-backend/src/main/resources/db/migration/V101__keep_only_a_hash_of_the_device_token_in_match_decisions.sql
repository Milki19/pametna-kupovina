-- Potvrde spajanja proizvoda (V17) čuvale su token uređaja kao čist tekst,
-- a token je jedino čime se ulazi u nalog: ko vidi bazu ili backup, mogao je
-- da se predstavi kao taj telefon. Od sada se čuva samo otisak, isti kao za
-- uređaje (account_device.client_token_hash), a postojeći redovi se prevode.

UPDATE app.product_match_decision
   SET client_token = encode(sha256(convert_to(BTRIM(client_token), 'UTF8')), 'hex')
 WHERE client_token IS NOT NULL;

-- Povratne informacije su inače samo za dopisivanje (V17); ovo je jedina
-- izmena koju smeju da prime, i ne menja ništa osim oblika istog podatka.
ALTER TABLE app.product_match_feedback
    DISABLE TRIGGER trg_product_match_feedback_append_only;
UPDATE app.product_match_feedback
   SET client_token = encode(sha256(convert_to(BTRIM(client_token), 'UTF8')), 'hex');
ALTER TABLE app.product_match_feedback
    ENABLE TRIGGER trg_product_match_feedback_append_only;

ALTER TABLE app.product_match_decision
    RENAME COLUMN client_token TO client_token_hash;
ALTER TABLE app.product_match_feedback
    RENAME COLUMN client_token TO client_token_hash;

ALTER TABLE app.product_match_decision
    DROP CONSTRAINT chk_product_match_decision_client_token_not_blank,
    ADD CONSTRAINT chk_product_match_decision_client_token_hash
        CHECK (client_token_hash IS NULL OR client_token_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE app.product_match_feedback
    DROP CONSTRAINT chk_product_match_feedback_client_token_not_blank,
    ADD CONSTRAINT chk_product_match_feedback_client_token_hash
        CHECK (client_token_hash ~ '^[0-9a-f]{64}$');
