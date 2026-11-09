package rs.pametnakupovina.backend.shoppinglist;

/**
 * The three plans shown side by side, any of them null where none was
 * found: Jedna stanica, Najbolje ukupno, Najjeftinija korpa.
 */
record PlanSet<P extends CostedPlan>(P singleStore, P bestTotal, P lowestBasket) {

    PlanSet<P> withSingleStore(P plan) {
        return new PlanSet<>(plan, bestTotal, lowestBasket);
    }

    PlanSet<P> withBestTotal(P plan) {
        return new PlanSet<>(singleStore, plan, lowestBasket);
    }

    PlanSet<P> withLowestBasket(P plan) {
        return new PlanSet<>(singleStore, bestTotal, plan);
    }
}
