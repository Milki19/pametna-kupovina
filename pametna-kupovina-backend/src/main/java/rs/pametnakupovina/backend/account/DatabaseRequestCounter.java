package rs.pametnakupovina.backend.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Counts in Postgres, so every backend instance sees the same count and a
 * restart forgives no one. The window is cut on the database clock, so two
 * instances with drifting clocks still share one window.
 */
@Component
public class DatabaseRequestCounter implements RequestCounter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRequestCounter.class);

    private final JdbcClient jdbcClient;

    public DatabaseRequestCounter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public int countAndGet(String key, Duration window) {
        try {
            return jdbcClient.sql("""
                            INSERT INTO app.request_count AS counted (counter_key, window_start, uses)
                            VALUES (
                                :key,
                                TO_TIMESTAMP(FLOOR(EXTRACT(EPOCH FROM NOW()) / :seconds) * :seconds),
                                1
                            )
                            ON CONFLICT (counter_key, window_start)
                                DO UPDATE SET uses = counted.uses + 1
                            RETURNING uses
                            """)
                    .param("key", key)
                    .param("seconds", window.toSeconds())
                    .query(Integer.class)
                    .single();
        } catch (DataAccessException countingFailed) {
            // A counter that cannot count must not be what turns shoppers away.
            log.warn("Brojanje zahteva nije uspelo, zahtev prolazi: {}", countingFailed.getMessage());
            return 0;
        }
    }

    /** Windows are a minute long; an hour of them is more than anyone reads. */
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    void forgetOldWindows() {
        jdbcClient.sql("DELETE FROM app.request_count WHERE window_start < NOW() - INTERVAL '1 hour'")
                .update();
    }
}
