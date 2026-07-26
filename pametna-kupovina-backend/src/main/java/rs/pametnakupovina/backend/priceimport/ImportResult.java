package rs.pametnakupovina.backend.priceimport;

public record ImportResult(
        Long importRunId,
        int rowsRead,
        int rowsSaved,
        int rowsSkipped,
        String status
) {
}