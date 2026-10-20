package rs.pametnakupovina.backend.receipt.serbia;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalShopNameTest {

    @Test
    void aNamedLocationIsWhatTheShopperWalkedInto() {
        assertThat(FiscalShopName.of("1207756-PLEASURE PARK ČAIR", "RESTAURANT PLEASURE PARK ČAIR"))
                .isEqualTo("PLEASURE PARK ČAIR");
        assertThat(FiscalShopName.of("Lidl prodavnica 137", "Lidl Srbija KD"))
                .isEqualTo("Lidl prodavnica 137");
    }

    @Test
    void aLocationThatIsOnlyANumberSaysNothingWithoutTheCompany() {
        assertThat(FiscalShopName.of("1205981-Prodavnica 137", "Lidl Srbija KD"))
                .isEqualTo("Lidl Srbija KD");
        assertThat(FiscalShopName.of("1205981-ПРОДАВНИЦА БР. 12", "Lidl Srbija KD"))
                .isEqualTo("Lidl Srbija KD");
        assertThat(FiscalShopName.of("Objekat 5", "FIRMA DOO")).isEqualTo("FIRMA DOO");
        assertThat(FiscalShopName.of("1205981-137", "Lidl Srbija KD"))
                .isEqualTo("Lidl Srbija KD");
    }

    @Test
    void whatThereIsIsBetterThanNothing() {
        assertThat(FiscalShopName.of("", "FIRMA DOO")).isEqualTo("FIRMA DOO");
        assertThat(FiscalShopName.of("Prodavnica 137", "")).isEqualTo("Prodavnica 137");
        assertThat(FiscalShopName.of(null, null)).isNull();
    }
}
