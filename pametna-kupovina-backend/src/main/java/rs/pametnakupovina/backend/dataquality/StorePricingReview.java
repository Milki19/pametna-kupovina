package rs.pametnakupovina.backend.dataquality;

public record StorePricingReview(
        long storeId,
        String retailerCode,
        String externalCode,
        String storeName,
        String address,
        String city,
        String storeFormatCode,
        String storeFormatName,
        boolean active,
        String geocodingStatus,
        boolean hasCoordinates,
        boolean pricingEligible,
        String pricingIneligibilityReason,
        String sourceCode
) {
}
