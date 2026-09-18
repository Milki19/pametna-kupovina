-- The separate import worker is gone: the server runs the daily cycle in the
-- backend itself, so nothing writes or reads this heartbeat any more.
DROP TABLE IF EXISTS app.import_worker_heartbeat;
