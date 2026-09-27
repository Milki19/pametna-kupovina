-- Each market runs its own daily price cycle on its own clock, so a cycle
-- belongs to a market, and one market's running cycle does not stop
-- another's. Every cycle so far was Serbia's, the default market.
ALTER TABLE app.price_refresh_cycle
    ADD COLUMN market_id SMALLINT NOT NULL DEFAULT app.default_market_id()
        CONSTRAINT fk_price_refresh_cycle_market REFERENCES app.market (id);

DROP INDEX app.price_refresh_one_running;

CREATE UNIQUE INDEX price_refresh_one_running
    ON app.price_refresh_cycle (market_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_price_refresh_cycle_market_date
    ON app.price_refresh_cycle (market_id, cycle_date);
