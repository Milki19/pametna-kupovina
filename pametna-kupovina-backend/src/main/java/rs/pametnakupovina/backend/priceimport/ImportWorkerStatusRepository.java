package rs.pametnakupovina.backend.priceimport;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;

@Repository
public class ImportWorkerStatusRepository {

    private final JdbcClient jdbcClient;

    public ImportWorkerStatusRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ImportWorkerStatus latestStatus(Duration maximumAge) {
        return jdbcClient.sql("""
                        SELECT instance_id,
                               started_at,
                               heartbeat_at,
                               heartbeat_at >= NOW()
                                   - (? * INTERVAL '1 millisecond') AS healthy
                        FROM app.import_worker_heartbeat
                        ORDER BY heartbeat_at DESC
                        LIMIT 1
                        """)
                .param(1, maximumAge.toMillis())
                .query((resultSet, rowNumber) -> {
                    return new ImportWorkerStatus(
                            resultSet.getString("instance_id"),
                            resultSet.getTimestamp("started_at").toInstant(),
                            resultSet.getTimestamp("heartbeat_at").toInstant(),
                            resultSet.getBoolean("healthy")
                    );
                })
                .optional()
                .orElse(new ImportWorkerStatus(
                        null,
                        null,
                        null,
                        false
                ));
    }
}
