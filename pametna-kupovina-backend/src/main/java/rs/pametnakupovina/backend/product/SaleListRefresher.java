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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps app.current_sale_list (V117) in step with the prices: after a
 * chain's prices change, as soon as a new day starts in any market (a sale
 * ends at midnight there and another one starts), and at least every hour.
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

    // Every minute, so a market's new day reaches the list right after its
    // midnight; most checks only read the markets' time zones.
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void refreshWhenDue() {
        if (stale.get() || due(refreshedAt, Instant.now(), marketTimeZones())) {
            refresh();
        }
    }

    /**
     * Due when the list is older than MAX_AGE or was read on an earlier day
     * than today somewhere a market is.
     */
    static boolean due(Instant refreshedAt, Instant now, Collection<ZoneId> timeZones) {
        if (refreshedAt.plus(MAX_AGE).isBefore(now)) {
            return true;
        }
        for (ZoneId zone : timeZones) {
            if (LocalDate.ofInstant(refreshedAt, zone).isBefore(LocalDate.ofInstant(now, zone))) {
                return true;
            }
        }
        return false;
    }

    private List<ZoneId> marketTimeZones() {
        try {
            return jdbcClient.sql("SELECT DISTINCT time_zone FROM app.market WHERE active")
                    .query(String.class)
                    .list()
                    .stream()
                    .map(ZoneId::of)
                    .toList();
        } catch (DataAccessException failed) {
            log.warn("Vremenske zone tržišta nisu pročitane: {}", failed.getMessage());
            return List.of();
        }
    }

    /** Reads today's sales again; the old list stays readable meanwhile. */
    public void refresh() {
        stale.set(false);
        long started = System.nanoTime();
        // The day the list is read on: taken before the read, so a midnight
        // during it brings another one.
        Instant reading = Instant.now();
        try {
            jdbcClient.sql("REFRESH MATERIALIZED VIEW CONCURRENTLY app.current_sale_list").update();
            refreshedAt = reading;
            log.info("Lista akcija osvežena za {} ms", (System.nanoTime() - started) / 1_000_000);
        } catch (DataAccessException failed) {
            stale.set(true);
            log.warn("Osvežavanje liste akcija nije uspelo: {}", failed.getMessage());
        }
    }
}
