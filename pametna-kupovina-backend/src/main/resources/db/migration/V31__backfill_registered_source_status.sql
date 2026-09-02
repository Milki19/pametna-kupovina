-- Registar je uveden nakon postojećih importa. Preuzimamo poslednji poznati
-- ishod po retaileru da monitoring ne počne lažno sa NEVER_RUN stanjem.
WITH latest_run AS (
    SELECT DISTINCT ON (run.retailer_id)
           run.retailer_id,
           run.status,
           run.started_at,
           run.finished_at,
           run.snapshot_date,
           run.checksum,
           run.error_message
    FROM app.import_run AS run
    ORDER BY run.retailer_id,
             run.started_at DESC,
             run.id DESC
)
UPDATE app.retailer_data_source AS source
SET last_status = latest_run.status,
    last_started_at = latest_run.started_at,
    last_success_at = CASE
        WHEN latest_run.status IN ('SUCCEEDED', 'SUCCEEDED_WITH_ERRORS')
            THEN latest_run.finished_at
        ELSE source.last_success_at
    END,
    last_snapshot_date = latest_run.snapshot_date,
    last_checksum = latest_run.checksum,
    last_error = latest_run.error_message,
    updated_at = NOW()
FROM latest_run
WHERE source.source_type = 'PRICE_CATALOG'
  AND latest_run.retailer_id = source.retailer_id;
