package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;
import java.util.List;

public record ProductMatchScore(
        BigDecimal totalScore,
        BigDecimal nameContribution,
        BigDecimal brandContribution,
        BigDecimal packageContribution,
        List<String> reasons
) {

    public ProductMatchScore {
        reasons = List.copyOf(reasons);
    }
}
