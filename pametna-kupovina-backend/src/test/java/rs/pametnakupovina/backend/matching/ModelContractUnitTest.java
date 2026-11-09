package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The amount, sale mode and whole-word rules of the model contract (V155). */
class ModelContractUnitTest {

    @Test
    void threeLemonsAreThreePiecesAndAboutThreeHundredGrams() {
        ListAmount lemons = new ListAmount(new BigDecimal("3"), BaseUnit.PIECE, null);
        assertThat(lemons.counted()).isTrue();
        assertThat(lemons.packs()).isEqualByComparingTo("1");
        assertThat(lemons.gramsForPieces(new BigDecimal("100"))).hasValueSatisfying(
                grams -> assertThat(grams).isEqualByComparingTo("300"));
        assertThat(lemons.kilogramsForPieces(new BigDecimal("100"))).hasValueSatisfying(
                kg -> assertThat(kg).isEqualByComparingTo("0.3"));
        assertThat(lemons.gramsForPieces(null)).isEmpty();
    }

    @Test
    void twoLitresInTwoPacksAreNotCountedAsPieces() {
        ListAmount milk = ListAmount.fromColumns(new BigDecimal("1000"), "ml", new BigDecimal("2"));
        assertThat(milk.unit()).isEqualTo(BaseUnit.MILLILITER);
        assertThat(milk.total()).hasValueSatisfying(total -> assertThat(total).isEqualByComparingTo("2000"));
        assertThat(milk.gramsForPieces(new BigDecimal("100"))).isEmpty();

        ListAmount bread = ListAmount.fromColumns(null, null, null);
        assertThat(bread.number()).isNull();
        assertThat(bread.packs()).isEqualByComparingTo("1");
        assertThat(bread.total()).isEmpty();
    }

    @Test
    void anAmountNeedsBothNumberAndUnit() {
        assertThatThrownBy(() -> new ListAmount(BigDecimal.ONE, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListAmount(BigDecimal.ZERO, BaseUnit.GRAM, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ListAmount.packsOnly(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saleModeReadsWhatThePriceListsSay() {
        assertThat(SaleMode.of(null, "kg", null, null)).isEqualTo(SaleMode.LOOSE_KG);
        assertThat(SaleMode.of(null, "kg", new BigDecimal("500"), "g")).isEqualTo(SaleMode.LOOSE_KG);
        assertThat(SaleMode.of(null, null, new BigDecimal("500"), "g")).isEqualTo(SaleMode.PACKAGE);
        assertThat(SaleMode.of(null, "kom", null, null)).isEqualTo(SaleMode.PIECE);
        assertThat(SaleMode.of(null, null, new BigDecimal("6"), "piece")).isEqualTo(SaleMode.PIECE);
        assertThat(SaleMode.of(null, null, null, null)).isNull();
        // Maxi "Mango komad" priced per kg in the list, set by hand.
        assertThat(SaleMode.of("PIECE", "kg", null, null)).isEqualTo(SaleMode.PIECE);
    }

    @Test
    void aWordMatchesOnlyWhole() {
        assertThat(WholeWords.contains("kinder cards 128g", "kinoa")).isFalse();
        assertThat(WholeWords.contains("kinoa bela 500g", "kinoa")).isTrue();
        assertThat(WholeWords.contains("coca cola zero 1,5l", "coca cola zero")).isTrue();
        assertThat(WholeWords.contains("coca cola 1,5l zero", "coca cola zero")).isFalse();
        assertThat(WholeWords.contains("kafamat", "kafa")).isFalse();
        assertThat(WholeWords.contains("mleko 2,8% mm", "2")).isTrue();
        assertThat(WholeWords.contains("", "kafa")).isFalse();
        assertThat(WholeWords.contains("kafa", "")).isFalse();
        assertThat(WholeWords.startsWith("orasi jezgro 100g", "orasi")).isTrue();
        assertThat(WholeWords.startsWith("mesavina orasi suvo voce", "orasi")).isFalse();
    }

    @Test
    void theSqlPatternIsWholeWordsToo() {
        assertThat(WholeWords.sqlPattern("coca cola zero")).isEqualTo("\\mcoca\\s+cola\\s+zero\\M");
        assertThat(WholeWords.sqlPattern("kinoa")).isEqualTo("\\mkinoa\\M");
    }
}
