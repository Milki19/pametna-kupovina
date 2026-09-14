package rs.pametnakupovina.backend.matching;

public record ProductMatchFeedbackRequest(
        String clientToken,
        ProductMatchFeedbackAction action,
        Long selectedCanonicalProductId,
        String note,
        Long selectedProductFamilyId
) {

    public ProductMatchFeedbackRequest(
            String clientToken,
            ProductMatchFeedbackAction action,
            Long selectedCanonicalProductId,
            String note
    ) {
        this(clientToken, action, selectedCanonicalProductId, note, null);
    }
}
