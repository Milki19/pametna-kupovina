package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductMatchThresholdPolicyTest {

    private final ProductMatchThresholdPolicy policy =
            new ProductMatchThresholdPolicy();

    @Test
    void scoreAtAutoAcceptThresholdIsAutomaticallyAccepted() {
        assertThat(policy.classify(score("0.9200")))
                .isEqualTo(ProductMatchStatus.AUTO_ACCEPTED);
    }

    @Test
    void scoreImmediatelyBelowAutoAcceptThresholdNeedsConfirmation() {
        assertThat(policy.classify(score("0.9199")))
                .isEqualTo(ProductMatchStatus.NEEDS_CONFIRMATION);
    }

    @Test
    void scoreAtConfirmationThresholdNeedsConfirmation() {
        assertThat(policy.classify(score("0.7500")))
                .isEqualTo(ProductMatchStatus.NEEDS_CONFIRMATION);
    }

    @Test
    void scoreBelowConfirmationThresholdRemainsUnmatched() {
        assertThat(policy.classify(score("0.7499")))
                .isEqualTo(ProductMatchStatus.UNMATCHED);
    }

    @Test
    void missingCandidateRemainsUnmatched() {
        assertThat(policy.classify(Optional.empty()))
                .isEqualTo(ProductMatchStatus.UNMATCHED);
    }

    @Test
    void scoreOutsideValidRangeIsRejected() {
        assertThatThrownBy(() -> policy.classify(score("1.0001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Matching score mora biti između 0 i 1");

        assertThatThrownBy(() -> policy.classify(score("-0.0001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Matching score mora biti između 0 i 1");
    }

    private Optional<BigDecimal> score(String value) {
        return Optional.of(new BigDecimal(value));
    }
}
