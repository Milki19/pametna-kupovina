-- A chain that passed the probe and now has a price source is neither
-- "discovered" nor merely "approved": the daily cycle imports it.
ALTER TABLE app.government_dataset_candidate
    DROP CONSTRAINT chk_government_candidate_status;

ALTER TABLE app.government_dataset_candidate
    ADD CONSTRAINT chk_government_candidate_status
        CHECK (review_status IN (
            'DISCOVERED',
            'APPROVED',
            'IGNORED',
            'REGISTERED'
        ));
