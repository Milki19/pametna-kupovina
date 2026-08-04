package rs.pametnakupovina.backend.priceimport;

import java.time.LocalDate;

public record ImportResult(
        Long importRunId,
        LocalDate snapshotDate,
        int rowsRead,
        int rowsSelected,
        int rowsSaved,
        int rowsSkipped,
        String status
) {
}