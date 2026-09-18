-- Before a chain goes live its published file is read once without saving a
-- single price: how much of it we can parse, which day it is for and how many
-- price lists it holds. The verdict stays next to the candidate so the owner
-- sees why a chain is waiting.
ALTER TABLE app.government_dataset_candidate
    ADD COLUMN probed_at TIMESTAMPTZ,
    ADD COLUMN probe_verdict VARCHAR(20),
    ADD COLUMN probe_summary TEXT;

ALTER TABLE app.government_dataset_candidate
    ADD CONSTRAINT chk_government_candidate_probe_verdict
        CHECK (
            probe_verdict IS NULL
                OR probe_verdict IN ('READY', 'NEEDS_REVIEW', 'REJECTED')
        );

COMMENT ON COLUMN app.government_dataset_candidate.probe_verdict IS
    'Ishod probnog čitanja cenovnika: READY, NEEDS_REVIEW ili REJECTED.';
