package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An import that stopped writing three hours ago is over; one that moved a
 * minute ago is still running, and so is its source.
 */
@SpringBootTest
@Testcontainers
class ImportRunRecoveryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private ImportRunRecovery recovery;

    @Autowired
    private JdbcClient jdbcClient;

    private long source(long retailerId, String code, String startedAgo) {
        return jdbcClient.sql("""
                        INSERT INTO app.retailer_data_source (
                            retailer_id, code, source_type, parser_profile,
                            last_status, last_started_at
                        )
                        VALUES (?, ?, 'PRICE_CATALOG', 'PRAVILNIK_76_2026_CSV',
                                'RUNNING', NOW() - CAST(? AS INTERVAL))
                        RETURNING id
                        """)
                .param(retailerId)
                .param(code)
                .param(startedAgo)
                .query(Long.class)
                .single();
    }

    private long run(long retailerId, long sourceId, String progressAgo) {
        return jdbcClient.sql("""
                        INSERT INTO app.import_run (
                            retailer_id, data_source_id, source_url, status, stage,
                            started_at, last_progress_at
                        )
                        VALUES (?, ?, 'https://example.test/cenovnik.csv', 'RUNNING', 'WRITING',
                                NOW() - CAST(? AS INTERVAL), NOW() - CAST(? AS INTERVAL))
                        RETURNING id
                        """)
                .param(retailerId)
                .param(sourceId)
                .param(progressAgo)
                .param(progressAgo)
                .query(Long.class)
                .single();
    }

    private String status(String sql, long id) {
        return jdbcClient.sql(sql).param(id).query(String.class).single();
    }

    @Test
    void anImportCutOffByARestartIsClosed() {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('RECOVERY', 'Recovery', 'https://example.test/cenovnik.csv')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        long stuckSource = source(retailerId, "STUCK", "3 hours");
        long stuckRun = run(retailerId, stuckSource, "3 hours");
        long liveSource = source(retailerId, "LIVE", "1 minute");
        long liveRun = run(retailerId, liveSource, "1 minute");

        assertThat(recovery.recover()).isEqualTo(1);

        assertThat(status("SELECT status FROM app.import_run WHERE id = ?", stuckRun)).isEqualTo("FAILED");
        assertThat(status("SELECT error_message FROM app.import_run WHERE id = ?", stuckRun))
                .isEqualTo(ImportRunRecovery.INTERRUPTED);
        assertThat(status("SELECT last_status FROM app.retailer_data_source WHERE id = ?", stuckSource))
                .isEqualTo("FAILED");
        assertThat(status("SELECT status FROM app.import_run WHERE id = ?", liveRun)).isEqualTo("RUNNING");
        assertThat(status("SELECT last_status FROM app.retailer_data_source WHERE id = ?", liveSource))
                .isEqualTo("RUNNING");
    }
}
