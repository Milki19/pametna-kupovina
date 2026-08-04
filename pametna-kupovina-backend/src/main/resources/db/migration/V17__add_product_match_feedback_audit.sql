ALTER TABLE app.product_match_decision
    ADD COLUMN client_token VARCHAR(100);

ALTER TABLE app.product_match_decision
    ADD CONSTRAINT chk_product_match_decision_client_token_not_blank
        CHECK (
            client_token IS NULL
                OR BTRIM(client_token) <> ''
        );

CREATE INDEX idx_product_match_decision_client_query
    ON app.product_match_decision (client_token, normalized_query)
    WHERE client_token IS NOT NULL;

CREATE TABLE app.product_match_feedback
(
    id                            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decision_id                   BIGINT NOT NULL,
    client_token                  VARCHAR(100) NOT NULL,
    action                        VARCHAR(20) NOT NULL,
    selected_canonical_product_id BIGINT,
    note                          VARCHAR(500),
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_match_feedback_decision
        FOREIGN KEY (decision_id)
            REFERENCES app.product_match_decision (id),

    CONSTRAINT fk_product_match_feedback_selected_product
        FOREIGN KEY (selected_canonical_product_id)
            REFERENCES app.canonical_product (id),

    CONSTRAINT chk_product_match_feedback_client_token_not_blank
        CHECK (BTRIM(client_token) <> ''),

    CONSTRAINT chk_product_match_feedback_action
        CHECK (action IN ('CONFIRMED', 'REJECTED')),

    CONSTRAINT chk_product_match_feedback_action_product_pair
        CHECK (
            (
                action = 'CONFIRMED'
                    AND selected_canonical_product_id IS NOT NULL
            )
                OR (
                    action = 'REJECTED'
                        AND selected_canonical_product_id IS NULL
                )
        ),

    CONSTRAINT chk_product_match_feedback_note_not_blank
        CHECK (note IS NULL OR BTRIM(note) <> '')
);

CREATE INDEX idx_product_match_feedback_decision_id
    ON app.product_match_feedback (decision_id);

CREATE INDEX idx_product_match_feedback_client_created
    ON app.product_match_feedback (client_token, created_at DESC, id DESC);

CREATE INDEX idx_product_match_feedback_selected_product
    ON app.product_match_feedback (selected_canonical_product_id)
    WHERE selected_canonical_product_id IS NOT NULL;

CREATE FUNCTION app.prevent_product_match_feedback_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS
$$
BEGIN
    RAISE EXCEPTION
        'product_match_feedback is append-only';
END;
$$;

CREATE TRIGGER trg_product_match_feedback_append_only
    BEFORE UPDATE OR DELETE
    ON app.product_match_feedback
    FOR EACH ROW
EXECUTE FUNCTION app.prevent_product_match_feedback_mutation();
