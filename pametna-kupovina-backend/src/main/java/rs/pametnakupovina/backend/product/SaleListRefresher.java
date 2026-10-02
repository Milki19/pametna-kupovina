package rs.pametnakupovina.backend.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps app.current_sale_list (V117) in step with the prices: after a
 * chain's prices change, and at least every hour so that a sale stops
 * counting soon after its last day.
 */
@Component
public class SaleListRefresher {

    private static final Logger log = LoggerFactory.getLogger(SaleListRefresher.class);

    static final Duration MAX_AGE = Duration.ofHours(1);

    private final JdbcClient jdbcClient;
    // Stale from the start: the list may be from before the backend stopped.
    private final AtomicBoolean stale = new AtomicBoolean(true);
    private volatile Instant refreshedAt = Instant.EPOCH;

    public SaleListRefresher(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** A chain's prices changed; inside a transaction, once it commits. */
    public void pricesChanged() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    stale.set(true);
                }
            });
        } else {
            stale.set(true);
        }
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    void refreshWhenDue() {
        if (stale.get() || refreshedAt.plus(MAX_AGE).isBefore(Instant.now())) {
            refresh();
        }
    }

    /** Reads today's sales again; the old list stays readable meanwhile. */
    public void refresh() {
        stale.set(false);
        long started = System.nanoTime();
        try {
            jdbcClient.sql("REFRESH MATERIALIZED VIEW CONCURRENTLY app.current_sale_list").update();
            refreshedAt = Instant.now();
            log.info("Lista akcija osvežena za {} ms", (System.nanoTime() - started) / 1_000_000);
        } catch (DataAccessException failed) {
            stale.set(true);
            log.warn("Osvežavanje liste akcija nije uspelo: {}", failed.getMessage());
        }
    }
}
