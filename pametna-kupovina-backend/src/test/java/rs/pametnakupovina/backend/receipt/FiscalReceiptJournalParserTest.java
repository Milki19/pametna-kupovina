package rs.pametnakupovina.backend.receipt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uzorak je pravi račun koji Poreska uprava i danas vraća na svom linku
 * (objavljen kao javni primer u biblioteci `turanjanin/serbian-fiscal-receipts-parser`).
 * Stavke u njihovom JSON-u ne stoje kao podaci nego kao otkucan račun, pa se
 * čita taj tekst.
 */
class FiscalReceiptJournalParserTest {

    private final FiscalReceiptJournalParser parser =
            new FiscalReceiptJournalParser();

    @Test
    void aRealReceiptGivesUpItsShopAndItsLines() throws IOException {
        var parsed = parser.parse(sampleJournal());

        assertThat(parsed.shopName()).isEqualTo("PLEASURE PARK ČAIR");
        assertThat(parsed.taxIdentificationNumber()).isEqualTo("112392483");

        assertThat(parsed.items()).hasSize(3);
        assertThat(parsed.items().getFirst()).satisfies(item -> {
            assertThat(item.lineNumber()).isEqualTo(1);
            assertThat(item.name()).isEqualTo("Burito sa piletinom");
            assertThat(item.unitPrice()).isEqualByComparingTo(new BigDecimal("650.00"));
            assertThat(item.quantity()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(item.totalPrice()).isEqualByComparingTo(new BigDecimal("650.00"));
        });
        assertThat(parsed.items()).extracting(Receipt.ReceiptItem::name)
                .containsExactly(
                        "Burito sa piletinom",
                        "Grcki omlet",
                        "Elixir mix 0.33l"
                );

        // Zbir stavki je ono što na računu piše kao ukupan iznos.
        assertThat(parsed.items().stream()
                .map(Receipt.ReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("1500.00"));
    }

    /** „1.500,00" je hiljadu petsto, ne jedan i po. */
    @Test
    void thousandsAreNotDecimals() {
        var parsed = parser.parse(journalWith("""
                Roba na kilogram (Ђ)
                     1.250,00      1,235        1.543,75
                """));

        assertThat(parsed.items()).singleElement().satisfies(item -> {
            assertThat(item.unitPrice())
                    .isEqualByComparingTo(new BigDecimal("1250.00"));
            assertThat(item.quantity())
                    .isEqualByComparingTo(new BigDecimal("1.235"));
            assertThat(item.totalPrice())
                    .isEqualByComparingTo(new BigDecimal("1543.75"));
        });
    }

    /** Dugačak naziv kasa prelama; artikal je i dalje jedan. */
    @Test
    void aNameBrokenAcrossLinesStaysOneItem() {
        var parsed = parser.parse(journalWith("""
                MLEKO ZA KAFU DUGOTRAJNO
                DELIKATES 500 ML (Ђ)
                       149,99          2          299,98
                """));

        assertThat(parsed.items()).singleElement().satisfies(item ->
                assertThat(item.name())
                        .isEqualTo("MLEKO ZA KAFU DUGOTRAJNO DELIKATES 500 ML"));
    }

    @Test
    void nothingAtAllIsNotACrash() {
        assertThat(parser.parse(null).items()).isEmpty();
        assertThat(parser.parse("").items()).isEmpty();
        assertThat(parser.parse("bilo šta").items()).isEmpty();
    }

    private static String journalWith(String items) {
        return """
                ============ ФИСКАЛНИ РАЧУН ============
                               112392483
                     PRODAVNICA DOO
                       1207756-PRODAVNICA 1
                          НЕКА УЛИЦА 1
                              Београд
                Касир:                        Operater 1
                -------------ПРОМЕТ ПРОДАЈА-------------
                Артикли
                ========================================
                Назив   Цена         Кол.         Укупно
                """ + items + """
                ----------------------------------------
                Укупан износ:                   1.500,00
                """;
    }

    private static String sampleJournal() throws IOException {
        try (var stream = FiscalReceiptJournalParserTest.class.getResourceAsStream(
                "/receipts/primer-racuna.txt"
        )) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
