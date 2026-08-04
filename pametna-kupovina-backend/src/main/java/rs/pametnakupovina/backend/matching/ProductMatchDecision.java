package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;
import java.util.List;

public record ProductMatchDecision(
        Long decisionId,
        ProductMatchStatus status,
        Long matchedCanonicalProductId,
        BigDecimal score,
        BigDecimal autoAcceptThreshold,
        BigDecimal confirmationThreshold,
        String algorithmVersion,
        List<FuzzyProductCandidate> candidates
) {

    public ProductMatchDecision {
        candidates = List.copyOf(candidates);
    }
}
