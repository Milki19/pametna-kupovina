package rs.pametnakupovina.backend.matching;

import java.time.Instant;

public record ProductMatchFeedback(
        Long feedbackId,
        Long decisionId,
        ProductMatchFeedbackAction action,
        Long selectedCanonicalProductId,
        boolean reusable,
        Instant createdAt
) {
}
