package rs.pametnakupovina.backend.product;

public record ProductCatalogRefreshResult(
        long retailerId,
        String retailerCode,
        int familyCount,
        int retailerProductCount,
        int categorizedProductCount,
        int presenceCount,
        int pendingIdentityCandidateCount
) {
}
