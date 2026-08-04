CREATE TABLE app.product_match_decision
(
    id                           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    raw_query                    VARCHAR(500) NOT NULL,
    normalized_query             VARCHAR(500) NOT NULL,
    top_candidate_id             BIGINT,
    matched_canonical_product_id BIGINT,
    score                        NUMERIC(5, 4) NOT NULL,
    status                       VARCHAR(30) NOT NULL,
    algorithm_version            VARCHAR(100) NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_product_match_decision_top_candidate
        FOREIGN KEY (top_candidate_id)
            REFERENCES app.canonical_product (id),

    CONSTRAINT fk_product_match_decision_matched_product
        FOREIGN KEY (matched_canonical_product_id)
            REFERENCES app.canonical_product (id),

    CONSTRAINT chk_product_match_decision_raw_query_not_blank
        CHECK (BTRIM(raw_query) <> ''),

    CONSTRAINT chk_product_match_decision_normalized_query_not_blank
        CHECK (BTRIM(normalized_query) <> ''),

    CONSTRAINT chk_product_match_decision_score_range
        CHECK (score >= 0 AND score <= 1),

    CONSTRAINT chk_product_match_decision_status
        CHECK (
            status IN (
                'AUTO_ACCEPTED',
                'NEEDS_CONFIRMATION',
                'UNMATCHED'
            )
        ),

    CONSTRAINT chk_product_match_decision_algorithm_version_not_blank
        CHECK (BTRIM(algorithm_version) <> ''),

    CONSTRAINT chk_product_match_decision_auto_accept_consistency
        CHECK (
            (
                status = 'AUTO_ACCEPTED'
                    AND top_candidate_id IS NOT NULL
                    AND matched_canonical_product_id = top_candidate_id
            )
                OR (
                    status <> 'AUTO_ACCEPTED'
                        AND matched_canonical_product_id IS NULL
                )
        ),

    CONSTRAINT chk_product_match_decision_threshold_consistency
        CHECK (
            (
                status = 'AUTO_ACCEPTED'
                    AND score >= 0.9200
            )
                OR (
                    status = 'NEEDS_CONFIRMATION'
                        AND top_candidate_id IS NOT NULL
                        AND score >= 0.7500
                        AND score < 0.9200
                )
                OR (
                    status = 'UNMATCHED'
                        AND score < 0.7500
                )
        ),

    CONSTRAINT chk_product_match_decision_candidate_score_consistency
        CHECK (
            top_candidate_id IS NOT NULL
                OR score = 0
        )
);

CREATE INDEX idx_product_match_decision_top_candidate_id
    ON app.product_match_decision (top_candidate_id);

CREATE INDEX idx_product_match_decision_created_at
    ON app.product_match_decision (created_at);
