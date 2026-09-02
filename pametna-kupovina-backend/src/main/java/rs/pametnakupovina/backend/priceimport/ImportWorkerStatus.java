package rs.pametnakupovina.backend.priceimport;

import java.time.Instant;

public record ImportWorkerStatus(
        String instanceId,
        Instant startedAt,
        Instant heartbeatAt,
        boolean healthy
) {
}
