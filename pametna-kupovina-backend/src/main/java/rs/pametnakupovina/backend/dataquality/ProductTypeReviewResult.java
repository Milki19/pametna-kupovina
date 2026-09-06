package rs.pametnakupovina.backend.dataquality;

public record ProductTypeReviewResult(
        long retailerProductId,
        String action,
        String productTypeCode,
        boolean reviewed
) {
}
