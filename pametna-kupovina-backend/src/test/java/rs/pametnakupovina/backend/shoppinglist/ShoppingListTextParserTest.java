package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingListTextParserTest {

    private final ShoppingListTextParser parser =
            new ShoppingListTextParser();

    @Test
    void everyNonBlankLineBecomesOneItemAndOriginalLineIsKept() {
        ParsedShoppingListText result = parser.parse(
                "  Mleko 1 l  \r\n\r\n  \r\nHleb\n"
        );

        assertThat(result.items()).hasSize(2);
        assertThat(result.ignoredBlankLineCount()).isEqualTo(3);
        assertThat(result.items().get(0).name())
                .isEqualTo("Mleko 1 l");
        assertThat(result.items().get(0).rawInput())
                .isEqualTo("  Mleko 1 l  ");
        assertThat(result.items().get(1).rawInput())
                .isEqualTo("Hleb");
    }

    @Test
    void explicitPrefixAndSuffixQuantitiesAreParsed() {
        ParsedShoppingListText result = parser.parse("""
                2 x Mleko 1 l
                3kom Hleb
                Jogurt x4
                Jabuke - 1,5 kg
                """);

        assertThat(result.items())
                .extracting(ParsedShoppingListLine::name)
                .containsExactly(
                        "Mleko 1 l",
                        "Hleb",
                        "Jogurt",
                        "Jabuke - 1,5 kg"
                );

        assertThat(result.items())
                .extracting(ParsedShoppingListLine::quantity)
                .containsExactly(
                        new java.math.BigDecimal("2"),
                        new java.math.BigDecimal("3"),
                        new java.math.BigDecimal("4"),
                        java.math.BigDecimal.ONE
                );
    }

    @Test
    void numbersInProductNameOrPackageAreNotGuessedAsItemCount() {
        ParsedShoppingListText result = parser.parse("""
                Mleko 1 l
                7 Days kroasan
                Pelene 4 maxi
                """);

        assertThat(result.items())
                .extracting(ParsedShoppingListLine::quantity)
                .containsOnly(java.math.BigDecimal.ONE);

        assertThat(result.items())
                .extracting(ParsedShoppingListLine::name)
                .containsExactly(
                        "Mleko 1 l",
                        "7 Days kroasan",
                        "Pelene 4 maxi"
                );
    }
}
