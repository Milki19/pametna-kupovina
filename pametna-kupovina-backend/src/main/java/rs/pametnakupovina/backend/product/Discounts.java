package rs.pametnakupovina.backend.product;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** How a sale is told to a shopper: whole percent off the regular price. */
final class Discounts {

    private Discounts() {
    }

    /**
     * 266,99 down to 199,99 is 25 %. Null when there is nothing to compare
     * or less than half a percent comes off, as in app.current_sale (V115).
     */
    static Integer percent(BigDecimal regularPrice, BigDecimal salePrice) {
        if (regularPrice == null || salePrice == null
                || regularPrice.signum() <= 0
                || salePrice.compareTo(regularPrice) >= 0) {
            return null;
        }
        int percent = BigDecimal.ONE
                .subtract(salePrice.divide(regularPrice, 6, RoundingMode.HALF_UP))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
        return percent >= 1 ? percent : null;
    }
}
