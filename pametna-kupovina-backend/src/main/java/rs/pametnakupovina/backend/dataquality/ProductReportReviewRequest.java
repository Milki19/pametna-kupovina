package rs.pametnakupovina.backend.dataquality;

/** {@code status}: CONFIRMED when the report was right, REJECTED when not. */
public record ProductReportReviewRequest(String status) {
}
