-- Product matching on a shopping list now looks where the search box looks:
-- at products merged across chains, including the ones sold without a
-- barcode (a celery root, meat from the counter). Those have no canonical
-- product, so a decision or a confirmation has to be able to name the family
-- instead. Such a product is never accepted automatically; the shopper
-- confirms it.
--
-- The family ids are a record of what was suggested, not live links, so they
-- carry no foreign key and a later regrouping can neither block nor rewrite
-- them.

ALTER TABLE app.product_match_decision
    ADD COLUMN top_candidate_family_id BIGINT;

ALTER TABLE app.product_match_decision
    DROP CONSTRAINT chk_product_match_decision_candidate_score_consistency,
    DROP CONSTRAINT chk_product_match_decision_threshold_consistency;

ALTER TABLE app.product_match_decision
    ADD CONSTRAINT chk_product_match_decision_candidate_score_consistency
        CHECK (
            top_candidate_id IS NOT NULL
            OR top_candidate_family_id IS NOT NULL
            OR score = 0
        ),
    ADD CONSTRAINT chk_product_match_decision_threshold_consistency
        CHECK (
            (status = 'AUTO_ACCEPTED' AND score >= 0.9200)
            OR (
                status = 'NEEDS_CONFIRMATION'
                AND score >= 0.7500
                AND (
                    (top_candidate_id IS NOT NULL AND score < 0.9200)
                    OR (
                        top_candidate_id IS NULL
                        AND top_candidate_family_id IS NOT NULL
                    )
                )
            )
            OR (status = 'UNMATCHED' AND score < 0.7500)
        );

ALTER TABLE app.product_match_feedback
    ADD COLUMN selected_product_family_id BIGINT;

ALTER TABLE app.product_match_feedback
    DROP CONSTRAINT chk_product_match_feedback_action_product_pair;

ALTER TABLE app.product_match_feedback
    ADD CONSTRAINT chk_product_match_feedback_action_product_pair
        CHECK (
            (
                action = 'CONFIRMED'
                AND (selected_canonical_product_id IS NULL)
                    <> (selected_product_family_id IS NULL)
            )
            OR (
                action = 'REJECTED'
                AND selected_canonical_product_id IS NULL
                AND selected_product_family_id IS NULL
            )
        );
