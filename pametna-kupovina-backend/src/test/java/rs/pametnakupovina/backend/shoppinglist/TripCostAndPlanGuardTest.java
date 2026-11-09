package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The one trip cost and the one keeper of plan rules (V155). */
class TripCostAndPlanGuardTest {

    private static final TripPrices PRICES =
            new TripPrices(new BigDecimal("30"), new BigDecimal("600"), new BigDecimal("50"));

    @Test
    void theCostIsBasketKilometresHoursAndStops() {
        TripCost cost = TripCost.of(new BigDecimal("1000"), 4.2, 1800, 2, PRICES);
        assertThat(cost.travel()).isEqualByComparingTo("126.00");
        assertThat(cost.time()).isEqualByComparingTo("300.00");
        assertThat(cost.stops()).isEqualByComparingTo("100.00");
        assertThat(cost.total()).isEqualByComparingTo("1526.00");
        assertThat(cost.tripOnly()).isEqualByComparingTo("526.00");
    }

    @Test
    void onFootAKilometreIsFreeButTheHourCounts() {
        // 50 minutes of walking for 600 RSD (šesti test) costs 500 RSD of time.
        TripCost walking = TripCost.of(new BigDecimal("2868"), 4.0, 3000, 2,
                PRICES.forMode(TravelMode.WALKING));
        assertThat(walking.travel()).isEqualByComparingTo("0.00");
        assertThat(walking.time()).isEqualByComparingTo("500.00");
        assertThat(PRICES.forMode(TravelMode.DRIVING)).isEqualTo(PRICES);
    }

    record Plan(int coveredItems, long wantedItems, long usualItems,
                BigDecimal basketCost, BigDecimal totalCost, Set<Long> storeIds) implements CostedPlan {
        static Plan of(int covered, String basket, String total, long... stores) {
            Set<Long> ids = new java.util.HashSet<>();
            for (long store : stores) {
                ids.add(store);
            }
            return new Plan(covered, covered, covered, new BigDecimal(basket), new BigDecimal(total), ids);
        }
    }

    @Test
    void theCheapestBasketIsNeverDearerThanAnotherPlanOfTheSameItems() {
        Plan single = Plan.of(10, "2900", "3000", 1);
        Plan best = Plan.of(10, "2800", "2968", 1, 2);
        Plan lowest = Plan.of(10, "2868", "3100", 3, 4);
        PlanSet<Plan> guarded = PlanGuard.guard(new PlanSet<>(single, best, lowest));
        assertThat(guarded.lowestBasket()).isSameAs(best);
        assertThat(guarded.singleStore()).isSameAs(single);
        assertThat(guarded.bestTotal()).isSameAs(best);
    }

    @Test
    void aPlanWithFewerItemsDoesNotReplaceTheCheapestBasket() {
        Plan single = Plan.of(8, "2000", "2100", 1);
        Plan lowest = Plan.of(10, "2868", "3100", 3, 4);
        PlanSet<Plan> guarded = PlanGuard.guard(new PlanSet<>(single, null, lowest));
        assertThat(guarded.lowestBasket()).isSameAs(lowest);
        assertThat(PlanGuard.guard(new PlanSet<Plan>(null, null, null)).lowestBasket()).isNull();
    }

    @Test
    void rulesApplyInTheirOrder() {
        Plan a = Plan.of(1, "10", "10", 1);
        Plan b = Plan.of(1, "20", "20", 2);
        PlanGuard.Rule swap = new PlanGuard.Rule() {
            @Override
            public <P extends CostedPlan> PlanSet<P> apply(PlanSet<P> plans) {
                return new PlanSet<>(plans.bestTotal(), plans.singleStore(), plans.lowestBasket());
            }
        };
        PlanSet<Plan> twice = PlanGuard.guard(new PlanSet<>(a, b, null), List.of(swap, swap));
        assertThat(twice.singleStore()).isSameAs(a);
        assertThat(PlanGuard.RULES).hasAtLeastOneElementOfType(PlanGuard.LowestBasketIsLowest.class);
    }
}
