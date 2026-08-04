package rs.pametnakupovina.backend.matching;

record ReusableProductMatch(
        Long feedbackId,
        Long decisionId,
        ProductMatchFeedbackAction action,
        Long canonicalProductId
) {
}
