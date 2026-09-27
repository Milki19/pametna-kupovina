package rs.pametnakupovina.backend.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The count lives in the database, so a second backend instance, or the same
 * one after a restart, keeps counting where the first left off; old windows
 * are cleared away.
 */
@SpringBootTest
@Testcontainers
class DatabaseRequestCounterTest {

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

    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void twoInstancesShareOneCount() {
        var first = new DatabaseRequestCounter(jdbcClient);
        var second = new DatabaseRequestCounter(jdbcClient);

        assertThat(first.countAndGet("address:203.0.113.7", HOUR)).isEqualTo(1);
        assertThat(second.countAndGet("address:203.0.113.7", HOUR)).isEqualTo(2);
        assertThat(first.countAndGet("address:203.0.113.7", HOUR)).isEqualTo(3);
        assertThat(second.countAndGet("address:198.51.100.4", HOUR)).isEqualTo(1);
    }

    @Test
    void windowsOlderThanAnHourAreForgotten() {
        jdbcClient.sql("""
                        INSERT INTO app.request_count (counter_key, window_start, uses)
                        VALUES ('account:1', NOW() - INTERVAL '2 hours', 50),
                               ('account:2', NOW(), 5)
                        """)
                .update();

        new DatabaseRequestCounter(jdbcClient).forgetOldWindows();

        assertThat(jdbcClient.sql("SELECT counter_key FROM app.request_count WHERE counter_key LIKE 'account:%'")
                .query(String.class)
                .list())
                .containsExactly("account:2");
    }
}
