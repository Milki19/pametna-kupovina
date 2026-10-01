package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountsTest {

    @Test
    void aSaleIsTheWholePercentTakenOff() {
        assertThat(Discounts.percent(new BigDecimal("266.99"), new BigDecimal("199.99"))).isEqualTo(25);
        assertThat(Discounts.percent(new BigDecimal("100"), new BigDecimal("50"))).isEqualTo(50);
    }

    @Test
    void noLowerPriceIsNoSale() {
        assertThat(Discounts.percent(null, new BigDecimal("10"))).isNull();
        assertThat(Discounts.percent(new BigDecimal("10"), new BigDecimal("10"))).isNull();
        assertThat(Discounts.percent(new BigDecimal("10"), new BigDecimal("12"))).isNull();
        // 0,2 % is a rounding, and "-0%" would read as a sale.
        assertThat(Discounts.percent(new BigDecimal("500.00"), new BigDecimal("499.00"))).isNull();
    }
}
