package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

/** All amounts use baseUnit (g, ml, piece); unitPrice is per kg/l/piece. */
public record PurchaseQuantity(
        BigDecimal packages, BigDecimal packageSize, String baseUnit,
        BigDecimal targetAmount, BigDecimal suppliedAmount,
        BigDecimal extraAmount, BigDecimal unitPrice
) {}
