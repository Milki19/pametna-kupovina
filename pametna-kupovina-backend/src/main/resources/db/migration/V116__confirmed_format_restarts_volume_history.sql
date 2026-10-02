-- Confirming a source's new format count (format-count/acknowledge) says the
-- smaller or larger list is the chain's real shape. The volume guard still
-- took its median from the runs before that change, so once confirmed, the
-- next import of the same shape was rejected as VOLUME_DROP, and a rejected
-- run never enters the history, so the source stayed rejected for good.
--
-- From now on a confirmation records the run the history starts from. The
-- volume guard only reads runs from there on, so it is skipped until the new
-- shape has three accepted runs and then guards the new size as usual.

ALTER TABLE app.retailer_data_source
    ADD COLUMN volume_history_from_run_id BIGINT;

-- Sources confirmed before this column existed: their history starts at the
-- last run that was accepted for review, the run the confirmation was about.
UPDATE app.retailer_data_source source
SET volume_history_from_run_id = reviewed.run_id
FROM (
    SELECT data_source_id, MAX(id) AS run_id
    FROM app.import_run
    WHERE status = 'SUCCEEDED_FORMAT_REVIEW'
    GROUP BY data_source_id
) reviewed
WHERE reviewed.data_source_id = source.id
  AND source.acknowledged_format_count IS NOT NULL
  AND source.pending_format_count IS NULL;
