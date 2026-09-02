package rs.pametnakupovina.backend.priceimport;

import java.time.Instant;
import java.time.LocalDate;

public record RetailerDataSourceStatus(
        Long id,
        String retailerCode,
        String retailerName,
        String code,
        String sourceType,
        String parserProfile,
        String sourceUrl,
        String discoveryUrl,
        String priceScope,
        String scheduleCron,
        boolean active,
        String lastStatus,
        Instant lastStartedAt,
        Instant lastSuccessAt,
        LocalDate lastSnapshotDate,
        String lastError
) {
}
