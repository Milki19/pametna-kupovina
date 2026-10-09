package rs.pametnakupovina.backend.product;

/**
 * `retailerProductId` names the chain's listing the report is about, if one;
 * `storeId` the shop, for NOT_IN_STORE.
 */
public record ProductReportRequest(
        ProductReportReason reason,
        String note,
        Long retailerProductId,
        Long storeId
) {
    public ProductReportRequest(ProductReportReason reason, String note, Long retailerProductId) {
        this(reason, note, retailerProductId, null);
    }
}
