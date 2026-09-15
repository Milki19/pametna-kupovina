package rs.pametnakupovina.backend.priceimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * An import cut off by a restart or a crash stays RUNNING forever: on 13.09.
 * one IDEA and one Maxi import stopped while writing and still showed as
 * running two days later. An import that has not moved for two hours is
 * over; it is marked failed when the server starts and before each daily
 * cycle, so the alarm and the next import see the truth.
 */
@Component
public class ImportRunRecovery {

    static final String INTERRUPTED = "Prekinuto: server je stao pre kraja uvoza.";

    private static final Logger log = LoggerFactory.getLogger(ImportRunRecovery.class);

    private final JdbcClient jdbcClient;

    public ImportRunRecovery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recover();
    }

    /** @return how many interrupted imports were closed */
    public int recover() {
        int runs = jdbcClient.sql("""
                        UPDATE app.import_run
                        SET status = 'FAILED',
                            stage = 'FAILED',
                            finished_at = GREATEST(NOW(), started_at),
                            error_message = ?
                        WHERE status = 'RUNNING'
                          AND last_progress_at < NOW() - INTERVAL '2 hours'
                        """)
                .param(1, INTERRUPTED)
                .update();

        int sources = jdbcClient.sql("""
                        UPDATE app.retailer_data_source AS source
                        SET last_status = 'FAILED',
                            last_error = ?,
                            consecutive_success_count = 0,
                            consecutive_failure_count = source.consecutive_failure_count + 1,
                            updated_at = NOW()
                        WHERE source.last_status = 'RUNNING'
                          AND source.last_started_at < NOW() - INTERVAL '2 hours'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM app.import_run AS run
                              WHERE run.data_source_id = source.id
                                AND run.status = 'RUNNING'
                          )
                        """)
                .param(1, INTERRUPTED)
                .update();

        if (runs > 0 || sources > 0) {
            log.warn("Zatvoreno prekinutih uvoza: {}, izvora koji su ostali u toku: {}", runs, sources);
        }
        return runs;
    }
}
