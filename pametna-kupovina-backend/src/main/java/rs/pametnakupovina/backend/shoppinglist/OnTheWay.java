package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.util.List;

/**
 * Items worth less than a stop that only another shop would add to the
 * plan: they are bought on the way, and the plan stays with fewer shops.
 *
 * @param itemsCost  what the items cost in the plan that had them
 * @param extraCost  how much more that plan was in all, items included
 * @param extraSeconds how much longer it took
 */
public record OnTheWay(
        List<Long> itemIds,
        List<String> itemNames,
        BigDecimal itemsCost,
        BigDecimal extraCost,
        long extraSeconds,
        String currency
) {
    public OnTheWay {
        itemIds = List.copyOf(itemIds);
        itemNames = List.copyOf(itemNames);
    }
}
