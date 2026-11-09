package rs.pametnakupovina.backend.shoppinglist;

import java.util.List;

/**
 * The one keeper of the rules every set of plans obeys before it is shown
 * (V155 contract). The recommendation calls {@link #guard} after every
 * filter (radius, on foot, by car), not once at the end, so no filter can
 * undo a rule. A rule fixes the set, it does not reject it: the plans stay
 * the plans the search found, at most one is swapped for another of them.
 *
 * <p>Rules apply in this order: Najbolje ukupno is the lowest total of the
 * plans that buy the same, Najjeftinija korpa the lowest basket of them,
 * and two plans with the same shops and basket are one plan (šesti test
 * 6.11.: on foot Najbolje ukupno looked dearer than Najjeftinija korpa).
 */
final class PlanGuard {

    /** One rule over the three plans. */
    interface Rule {
        <P extends CostedPlan> PlanSet<P> apply(PlanSet<P> plans);
    }

    static final List<Rule> RULES = List.of(
            new BestTotalIsLowestTotal(),
            new LowestBasketIsLowest(),
            new SamePlanIsOnePlan());

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
     * Najbolje ukupno is never dearer in total than another shown plan that
     * buys as much, as asked: the total counts the basket, the way and the
     * time, on foot too (the hour at the shopper's price, the kilometre
     * free). Runs before {@link LowestBasketIsLowest}, so the basket rule
     * sees the plan Najbolje ukupno really is.
     */
    static final class BestTotalIsLowestTotal implements Rule {
        @Override
        public <P extends CostedPlan> PlanSet<P> apply(PlanSet<P> plans) {
            P best = plans.bestTotal();
            if (best == null) {
                return plans;
            }
            P lowest = best;
            for (P other : java.util.Arrays.asList(plans.singleStore(), plans.lowestBasket())) {
                if (other != null
                        && sameItemsAsAsked(other, best)
                        && other.totalCost().compareTo(lowest.totalCost()) < 0) {
                    lowest = other;
                }
            }
            return lowest == best ? plans : plans.withBestTotal(lowest);
        }
    }

    /**
     * Two plans with the same shops, the same basket and the same items are
     * one plan, and the set holds it once: the later plan becomes the same
     * object as the earlier (Jedna stanica, then Najbolje ukupno), so a
     * client that shows each plan once never shows it twice.
     */
    static final class SamePlanIsOnePlan implements Rule {
        @Override
        public <P extends CostedPlan> PlanSet<P> apply(PlanSet<P> plans) {
            PlanSet<P> one = plans;
            if (samePlan(one.bestTotal(), one.singleStore())) {
                one = one.withBestTotal(one.singleStore());
            }
            if (samePlan(one.lowestBasket(), one.bestTotal())) {
                one = one.withLowestBasket(one.bestTotal());
            } else if (samePlan(one.lowestBasket(), one.singleStore())) {
                one = one.withLowestBasket(one.singleStore());
            }
            return one;
        }
    }

    /** The same shops, basket and items: one plan shown under two names. */
    static boolean samePlan(CostedPlan one, CostedPlan other) {
        return one != null && other != null && one != other
                && one.storeIds().equals(other.storeIds())
                && one.basketCost().compareTo(other.basketCost()) == 0
                && sameItemsAsAsked(one, other)
                && one.usualItems() == other.usualItems();
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
