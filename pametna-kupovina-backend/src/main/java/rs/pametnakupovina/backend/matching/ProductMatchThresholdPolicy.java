package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

@Component
public class ProductMatchThresholdPolicy {

    private static final BigDecimal AUTO_ACCEPT_THRESHOLD =
            new BigDecimal("0.9200");

    private static final BigDecimal CONFIRMATION_THRESHOLD =
            new BigDecimal("0.7500");

    private static final String ALGORITHM_VERSION =
            "fuzzy-name-brand-package-v1";

    public ProductMatchStatus classify(
            Optional<BigDecimal> topCandidateScore
    ) {
        if (topCandidateScore.isEmpty()) {
            return ProductMatchStatus.UNMATCHED;
        }

        BigDecimal score = topCandidateScore.orElseThrow();
        validateScore(score);

        if (score.compareTo(AUTO_ACCEPT_THRESHOLD) >= 0) {
            return ProductMatchStatus.AUTO_ACCEPTED;
        }

        if (score.compareTo(CONFIRMATION_THRESHOLD) >= 0) {
            return ProductMatchStatus.NEEDS_CONFIRMATION;
        }

        return ProductMatchStatus.UNMATCHED;
    }

    public BigDecimal autoAcceptThreshold() {
        return AUTO_ACCEPT_THRESHOLD;
    }

    public BigDecimal confirmationThreshold() {
        return CONFIRMATION_THRESHOLD;
    }

    public String algorithmVersion() {
        return ALGORITHM_VERSION;
    }

    private void validateScore(BigDecimal score) {
        if (score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "Matching score mora biti između 0 i 1"
            );
        }
    }
}
