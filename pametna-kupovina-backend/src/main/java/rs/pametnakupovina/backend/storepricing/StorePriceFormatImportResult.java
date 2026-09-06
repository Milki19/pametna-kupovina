package rs.pametnakupovina.backend.storepricing;

public record StorePriceFormatImportResult(
        String retailerCode,
        int rowsRead,
        int storesLinked,
        int storesUnlinked,
        int pricingEligibleStores,
        String sourceUrl,
        String status
) {
}
