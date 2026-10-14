package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayNamesTest {

    @Test
    void spacesOfEveryKindAreOnePlainSpace() {
        assertThat(DisplayNames.cleanSpaces("  Col sensi  360 palmolive﻿ "))
                .isEqualTo("Col sensi 360 palmolive");
        assertThat(DisplayNames.cleanSpaces(null)).isNull();
    }

    @Test
    void aShopLosesTheChainsInternalNumber() {
        assertThat(DisplayNames.storeName("213 Orasac")).isEqualTo("Orasac");
        assertThat(DisplayNames.storeName("213 Orasac, Dobračina 2")).isEqualTo("Orasac, Dobračina 2");
        assertThat(DisplayNames.storeName("045 - Centar")).isEqualTo("Centar");
        assertThat(DisplayNames.storeName("Lidl 1")).isEqualTo("Lidl 1");
        assertThat(DisplayNames.storeName("24/7 market")).isEqualTo("24/7 market");
        assertThat(DisplayNames.storeName("015")).isEqualTo("015");
    }

    @Test
    void aProductLosesTheStockCodeInFront() {
        assertThat(DisplayNames.productName("369680 Dove gel 700 ml")).isEqualTo("Dove gel 700 ml");
        assertThat(DisplayNames.productName("7 days kroasan")).isEqualTo("7 days kroasan");
        assertThat(DisplayNames.productName("Mleko  2,8%  1L")).isEqualTo("Mleko 2,8% 1L");
        assertThat(DisplayNames.productName("100000")).isEqualTo("100000");
    }
}
