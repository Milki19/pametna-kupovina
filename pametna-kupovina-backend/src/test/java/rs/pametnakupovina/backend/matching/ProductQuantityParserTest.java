package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ProductQuantityParserTest {

    private final ProductQuantityParser parser =
            new ProductQuantityParser();

    @ParameterizedTest
    @MethodSource("quantityExamples")
    void convertsQuantitiesToComparableBaseUnits(
            String productName,
            String expectedValue,
            BaseUnit expectedUnit
    ) {
        ParsedQuantity result = parser.parse(productName)
                .orElseThrow();

        assertThat(result.value())
                .isEqualByComparingTo(new BigDecimal(expectedValue));

        assertThat(result.unit()).isEqualTo(expectedUnit);
    }

    @Test
    void returnsEmptyWhenQuantityIsMissingOrZero() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("Beli hleb")).isEmpty();
        assertThat(parser.parse("Sok 0 l")).isEmpty();
        assertThat(parser.parse("Sok -1 l")).isEmpty();
    }

    private static Stream<Arguments> quantityExamples() {
        return Stream.of(
                Arguments.of(
                        "Mleko 1 l",
                        "1000",
                        BaseUnit.MILLILITER
                ),
                Arguments.of(
                        "Млеко 1 л",
                        "1000",
                        BaseUnit.MILLILITER
                ),
                Arguments.of(
                        "Mleko 1000 ml",
                        "1000",
                        BaseUnit.MILLILITER
                ),
                Arguments.of(
                        "Mleko 1.000 ml",
                        "1000",
                        BaseUnit.MILLILITER
                ),
                Arguments.of(
                        "Jogurt 0,5 kg",
                        "500",
                        BaseUnit.GRAM
                ),
                Arguments.of(
                        "Jogurt 0.5 kg",
                        "500",
                        BaseUnit.GRAM
                ),
                Arguments.of(
                        "Keks 500 g",
                        "500",
                        BaseUnit.GRAM
                ),
                Arguments.of(
                        "Voda 2 x 1,5 l",
                        "3000",
                        BaseUnit.MILLILITER
                ),
                Arguments.of(
                        "Jaja 10 kom",
                        "10",
                        BaseUnit.PIECE
                )
        );
    }
}
