package rs.pametnakupovina.backend.matching;

public record ProductMatchFeedbackRequest(
        String clientToken,
        ProductMatchFeedbackAction action,
        Long selectedCanonicalProductId,
        String note
) {
}
