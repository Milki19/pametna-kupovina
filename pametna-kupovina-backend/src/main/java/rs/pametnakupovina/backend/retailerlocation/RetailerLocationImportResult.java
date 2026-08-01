package rs.pametnakupovina.backend.retailerlocation;

public record RetailerLocationImportResult(
        String retailerCode,
        int rowsRead,
        int rowsSaved,
        int rowsSkipped,
        String status
) {
}