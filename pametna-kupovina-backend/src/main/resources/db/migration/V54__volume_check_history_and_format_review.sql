-- Replace the live-state VOLUME_DROP baseline with a rolling median over
-- app.import_run history, and add a distinct-format-count signal that lets a
-- genuine catalog restructuring pass without weakening protection against a
-- plain broken/partial feed. See ficaFromSep12.md for the full rationale.

ALTER TABLE app.import_run
    ADD COLUMN store_id BIGINT REFERENCES app.store (id);

CREATE INDEX idx_import_run_volume_history
    ON app.import_run (retailer_id, data_source_id, store_id, id DESC)
    WHERE status IN ('SUCCEEDED', 'SUCCEEDED_WITH_ERRORS');

ALTER TABLE app.import_run
    DROP CONSTRAINT chk_import_run_status;

ALTER TABLE app.import_run
    ADD CONSTRAINT chk_import_run_status
        CHECK (
            status IN (
                       'RUNNING',
                       'SUCCEEDED',
                       'SUCCEEDED_WITH_ERRORS',
                       'SUCCEEDED_FORMAT_REVIEW',
                       'FAILED'
                )
            );

ALTER TABLE app.retailer_data_source
    DROP CONSTRAINT chk_retailer_data_source_status;

ALTER TABLE app.retailer_data_source
    ADD CONSTRAINT chk_retailer_data_source_status
        CHECK (last_status IN (
            'NEVER_RUN',
            'RUNNING',
            'SUCCEEDED',
            'SUCCEEDED_WITH_ERRORS',
            'SUCCEEDED_FORMAT_REVIEW',
            'FAILED'
        ));

ALTER TABLE app.retailer_data_source
    ADD COLUMN acknowledged_format_count INTEGER,
    ADD COLUMN pending_format_count INTEGER,
    ADD COLUMN format_count_flagged_at TIMESTAMPTZ;
