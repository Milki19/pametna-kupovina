package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.util.List;

public record RetailerBasketOption(
        String retailerCode,
        String retailerName,
        int matchedItems,
        int missingItems,
        boolean complete,
        BigDecimal totalPrice,
        List<RetailerItemOffer> items
) {
}