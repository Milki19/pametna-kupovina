package rs.pametnakupovina.backend.dataquality;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DataQualityReport(
        Instant generatedAt,
        String status,
        Summary summary,
        LocationSummary locations,
        List<RetailerQuality> retailers,
        List<SourceQuality> sources
) {
    public record Summary(
            long canonicalProducts,
            long productFamilies,
            long retailerProducts,
            long currentPriceOffers,
            long unmatchedRetailerProducts,
            long uncategorizedRetailerProducts,
            long typedRetailerProducts,
            long untypedRetailerProducts,
            long pendingProductTypeCandidates,
            long extractedProductAttributes,
            long pendingIdentityCandidates,
            long suspectedDuplicateProductFamilies,
            long conflictingCanonicalBarcodes,
            long invalidCurrentPriceOffers,
            long staleCurrentPriceOffers
    ) {
    }

    public record LocationSummary(
            long totalActive,
            long geocodedAndVerified,
            long pricingEligible,
            long pricingIneligible,
            long unsafePricingEligible
    ) {
    }

    public record RetailerQuality(
            String retailerCode,
            String retailerName,
            long retailerProducts,
            long matchedProducts,
            long categorizedProducts,
            long currentPriceOffers,
            LocalDate latestPriceDate,
            long activeStores,
            long pricingEligibleStores
    ) {
    }

    public record SourceQuality(
            long id,
            String retailerCode,
            String sourceCode,
            String sourceType,
            boolean active,
            String health,
            String lastStatus,
            Instant lastStartedAt,
            Instant lastSuccessAt,
            LocalDate lastSnapshotDate,
            Integer lastRowsRead,
            Integer lastRowsSaved,
            Integer expectedMinRowsSaved,
            Integer maxSuccessAgeHours,
            BigDecimal volumeRatio,
            BigDecimal minimumVolumeRatio,
            int consecutiveSuccessCount,
            int consecutiveFailureCount,
            String latestRunStage,
            Long latestRunDownloadedBytes,
            Instant latestRunLastProgressAt,
            List<String> alerts
    ) {
    }
}
