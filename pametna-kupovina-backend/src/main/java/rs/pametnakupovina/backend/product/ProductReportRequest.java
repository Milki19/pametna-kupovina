package rs.pametnakupovina.backend.product;

/** `retailerProductId` names the chain's listing the report is about, if one. */
public record ProductReportRequest(
        ProductReportReason reason,
        String note,
        Long retailerProductId
) {
}
