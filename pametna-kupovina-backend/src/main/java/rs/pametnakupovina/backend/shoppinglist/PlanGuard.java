package rs.pametnakupovina.backend.shoppinglist;

import java.util.List;

/**
 * The one keeper of the rules every set of plans obeys before it is shown
 * (V155 contract). The recommendation calls {@link #guard} after every
 * filter (radius, on foot, by car), not once at the end, so no filter can
 * undo a rule. A rule fixes the set, it does not reject it: the plans stay
 * the plans the search found, at most one is swapped for another of them.
 *
 * <p>Rules are added here, in the order they apply; S8 adds "Najbolje
 * ukupno is never dearer in total than a plan of the same coverage" and
 * "two equal plans are shown as one".
 */
final class PlanGuard {

    /** One rule over the three plans. */
    interface Rule {
        <P extends CostedPlan> PlanSet<P> apply(PlanSet<P> plans);
    }

    static final List<Rule> RULES = List.of(new LowestBasketIsLowest());

    private PlanGuard() {
    }

    static <P extends CostedPlan> PlanSet<P> guard(PlanSet<P> plans) {
        return guard(plans, RULES);
    }

    static <P extends CostedPlan> PlanSet<P> guard(PlanSet<P> plans, List<Rule> rules) {
        PlanSet<P> guarded = plans;
        for (Rule rule : rules) {
            guarded = rule.apply(guarded);
        }
        return guarded;
    }

    /** Plans a shopper compares by price: as many items, as asked. */
    static boolean sameItemsAsAsked(CostedPlan one, CostedPlan other) {
        return one.coveredItems() == other.coveredItems()
                && one.wantedItems() == other.wantedItems();
    }

    /**
     * Najjeftinija korpa is never dearer than another plan that covers as
     * much (test 1.11.: 3.831 against 3.621 RSD in Najbolje ukupno). As much
     * is as many items, as asked: the usual kind and size only orders the
     * plans that cost the same, or Najjeftinija stays dearer than Jedna
     * stanica (proba 5.11.: 4.069,87 against 3.829,87 RSD, where one shop's
     * Persil did not count as the usual one).
     */
    static final class LowestBasketIsLowest implements Rule {
        @Override
        public <P extends CostedPlan> PlanSet<P> apply(PlanSet<P> plans) {
            P lowest = plans.lowestBasket();
            if (lowest == null) {
                return plans;
            }
            P cheapest = lowest;
            for (P other : java.util.Arrays.asList(plans.bestTotal(), plans.singleStore())) {
                if (other != null
                        && sameItemsAsAsked(other, lowest)
                        && other.basketCost().compareTo(cheapest.basketCost()) < 0) {
                    cheapest = other;
                }
            }
            return cheapest == lowest ? plans : plans.withLowestBasket(cheapest);
        }
    }
}
