package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.util.Set;

/** What {@link PlanGuard} needs to know of a plan (V155 contract). */
interface CostedPlan {

    /** Items the plan buys. */
    int coveredItems();

    /** Items the plan buys as the list asked, not a substitute. */
    long wantedItems();

    /** Items the plan buys in the usual kind and size. */
    long usualItems();

    BigDecimal basketCost();

    /** {@link TripCost#total()}. */
    BigDecimal totalCost();

    /** The shops, by id, in no order: two plans with the same shops and offers are one. */
    Set<Long> storeIds();
}
