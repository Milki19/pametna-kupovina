package rs.pametnakupovina.backend.dataquality;

import java.math.BigDecimal;

public record ProductTypeCandidateReview(
        long retailerProductId,
        String retailerCode,
        String sourceProductKey,
        String productName,
        String sourceCategoryCode,
        String sourceCategoryName,
        String suggestedProductTypeCode,
        String suggestedProductTypeName,
        BigDecimal confidence,
        String predictionSource,
        String evidence,
        String algorithmVersion
) {
}
