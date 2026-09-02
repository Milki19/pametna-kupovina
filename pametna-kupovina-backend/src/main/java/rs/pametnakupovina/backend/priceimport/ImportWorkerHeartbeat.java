package rs.pametnakupovina.backend.priceimport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "price-import.worker.enabled",
        havingValue = "true"
)
public class ImportWorkerHeartbeat {

    private final JdbcClient jdbcClient;
    private final String instanceId;

    public ImportWorkerHeartbeat(
            JdbcClient jdbcClient,
            @Value("${price-import.worker.instance-id:local-worker}")
            String instanceId
    ) {
        this.jdbcClient = jdbcClient;
        this.instanceId = instanceId;
    }

    @Scheduled(
            fixedDelayString =
                    "${price-import.worker.heartbeat-interval-ms:30000}",
            initialDelayString = "0"
    )
    public void heartbeat() {
        jdbcClient.sql("""
                    INSERT INTO app.import_worker_heartbeat (
                        instance_id,
                        started_at,
                        heartbeat_at
                    )
                    VALUES (?, NOW(), NOW())
                    ON CONFLICT (instance_id)
                    DO UPDATE SET
                        heartbeat_at = NOW(),
                        updated_at = NOW()
                    """)
                .param(1, instanceId)
                .update();
    }

}
