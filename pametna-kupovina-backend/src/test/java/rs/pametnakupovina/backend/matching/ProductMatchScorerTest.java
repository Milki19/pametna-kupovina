package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMatchScorerTest {

    private final ProductMatchScorer scorer =
            new ProductMatchScorer(new ProductNameNormalizer());

    @Test
    void exactNameBrandAndPackageProduceMaximumScore() {
        ProductMatchScore score = scorer.score(
                "imlek mleko 1 l",
                Optional.of(quantity("1000", BaseUnit.MILLILITER)),
                new BigDecimal("1.0000"),
                "Imlek",
                new BigDecimal("1000"),
                "ml"
        );

        assertThat(score.totalScore())
                .isEqualByComparingTo("1.0000");
        assertThat(score.nameContribution())
                .isEqualByComparingTo("0.4118");
        assertThat(score.brandContribution())
                .isEqualByComparingTo("0.2941");
        assertThat(score.packageContribution())
                .isEqualByComparingTo("0.2941");
        assertThat(score.reasons()).containsExactly(
                "Naziv: sličnost 100.00%.",
                "Brend: \"Imlek\" je pronađen u upitu.",
                "Pakovanje: tačno podudaranje (1000 ml)."
        );
    }

    @Test
    void wrongBrandAndPackageDoNotAddTheirContributions() {
        ProductMatchScore score = scorer.score(
                "imlek mleko 1 l",
                Optional.of(quantity("1000", BaseUnit.MILLILITER)),
                new BigDecimal("0.9000"),
                "Meggle",
                new BigDecimal("1500"),
                "ml"
        );

        assertThat(score.totalScore())
                .isEqualByComparingTo("0.3706");
        assertThat(score.nameContribution())
                .isEqualByComparingTo("0.3706");
        assertThat(score.brandContribution()).isZero();
        assertThat(score.packageContribution()).isZero();
        assertThat(score.reasons())
                .anyMatch(reason -> reason.contains(
                        "nije pronađen u upitu"
                ));
        assertThat(score.reasons())
                .anyMatch(reason -> reason.contains(
                        "traženo 1000 ml, kandidat ima 1500 ml"
                ));
    }

    @Test
    void missingSignalsAreExplainedWithoutInventingAContribution() {
        ProductMatchScore score = scorer.score(
                "mleko",
                Optional.empty(),
                new BigDecimal("0.8000"),
                null,
                null,
                null
        );

        assertThat(score.totalScore())
                .isEqualByComparingTo("0.3294");
        assertThat(score.brandContribution()).isZero();
        assertThat(score.packageContribution()).isZero();
        assertThat(score.reasons()).contains(
                "Brend: kandidat nema naveden brend.",
                "Pakovanje: količina nije prepoznata u upitu."
        );
    }

    private ParsedQuantity quantity(
            String value,
            BaseUnit unit
    ) {
        return new ParsedQuantity(new BigDecimal(value), unit);
    }
}
