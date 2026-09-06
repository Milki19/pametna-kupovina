-- SUCCEEDED_WITH_ERRORS ima 21 znak. Prvobitna VARCHAR(20) kolona je
-- dozvoljavala vrednost kroz CHECK ograničenje, ali bi PostgreSQL odbio upis
-- pre nego što se ograničenje uopšte proveri.
ALTER TABLE app.import_run
    ALTER COLUMN status TYPE VARCHAR(30);
