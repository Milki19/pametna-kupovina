package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What every plan must keep to before it is shown (test 1.11.: "prašak za
 * veš Persil 4,5 kg" became nine boxes of 561 g discs at 9.531 RSD, and the
 * plan called the difference a saving). An offer that breaks a rule never
 * enters a plan, so no plan and no saving is built on it.
 */
final class PlanRules {

    /** More packs of one product than this only where the list asks, or they cost no more. */
    static final int MAX_PACKS = 4;

    /** A kilogram or a litre a pack: six litres of milk are six packs. */
    private static final BigDecimal WHOLE_UNIT = BigDecimal.valueOf(1000);

    /** Dearer than this many times the usual price for the item is no choice. */
    static final BigDecimal MAX_TIMES_MEDIAN = BigDecimal.valueOf(3);

    /** Fewer offers than this say nothing about the usual price. */
    private static final int MIN_OFFERS_FOR_MEDIAN = 3;

    private PlanRules() {
    }

    /**
     * Nine boxes of 561 g discs for 4,5 kg of powder break it: more packs
     * than four (or than the list asks for) of less than a kilogram or a
     * litre each, at more a gram than the item usually costs. Six one-litre
     * packs for six litres of milk do not, nor five 200 g cups that cost
     * less than the kilogram tub, nor six bottles asked as six.
     */
    static boolean reasonablePackCount(StoreItemOffer offer, Map<Long, BigDecimal> usualUnitPrices) {
        if (!manyPacks(offer)) {
            return true;
        }
        BigDecimal usual = usualUnitPrices.get(offer.itemId());
        BigDecimal unitPrice = unitPrice(offer);
        return usual != null && unitPrice != null && unitPrice.compareTo(usual) <= 0;
    }

    /** More than four packs, or than asked, of less than a kilogram or a litre. */
    static boolean manyPacks(StoreItemOffer offer) {
        PurchaseQuantity quantity = offer.purchaseQuantity();
        if (quantity == null || quantity.packages() == null) {
            return false;
        }
        BigDecimal asked = offer.requestedQuantity() == null
                ? BigDecimal.ONE
                : offer.requestedQuantity().setScale(0, RoundingMode.CEILING);
        if (quantity.packages().compareTo(BigDecimal.valueOf(MAX_PACKS).max(asked)) <= 0) {
            return false;
        }
        boolean wholeUnits = quantity.packageSize() != null
                && ("g".equals(quantity.baseUnit()) || "ml".equals(quantity.baseUnit()))
                && quantity.packageSize().compareTo(WHOLE_UNIT) >= 0;
        return !wholeUnits;
    }

    /**
     * What the item usually costs, per gram, millilitre or piece supplied:
     * the median of what the shops of this plan offer for it. Items with too
     * few offers have none, and no offer of theirs is held against it.
     */
    static Map<Long, BigDecimal> usualUnitPrices(Collection<StoreItemOffer> offers) {
        Map<Long, List<BigDecimal>> byItem = new HashMap<>();
        for (StoreItemOffer offer : offers) {
            BigDecimal unitPrice = unitPrice(offer);
            if (unitPrice != null) {
                byItem.computeIfAbsent(offer.itemId(), id -> new java.util.ArrayList<>()).add(unitPrice);
            }
        }
        Map<Long, BigDecimal> usual = new HashMap<>();
        byItem.forEach((itemId, prices) -> {
            if (prices.size() >= MIN_OFFERS_FOR_MEDIAN) {
                usual.put(itemId, median(prices));
            }
        });
        return usual;
    }

    /** No more than three times what the item usually costs. */
    static boolean usualPrice(StoreItemOffer offer, Map<Long, BigDecimal> usualUnitPrices) {
        BigDecimal usual = usualUnitPrices.get(offer.itemId());
        BigDecimal unitPrice = unitPrice(offer);
        return usual == null
                || unitPrice == null
                || unitPrice.compareTo(usual.multiply(MAX_TIMES_MEDIAN)) <= 0;
    }

    static boolean passes(StoreItemOffer offer, Map<Long, BigDecimal> usualUnitPrices) {
        return !offer.available()
                || (reasonablePackCount(offer, usualUnitPrices) && usualPrice(offer, usualUnitPrices));
    }

    /** The offers a plan may count on; the rest are as if the shop had none. */
    static List<StoreItemOffer> keep(List<StoreItemOffer> offers, Map<Long, BigDecimal> usualUnitPrices) {
        return offers.stream()
                .filter(offer -> passes(offer, usualUnitPrices))
                .toList();
    }

    /**
     * A pack more than a quarter over the amount, where no shop of the plan
     * had one that fits better (eggs, counted in pieces, already allow twice
     * the count). Shown with the item, so the shopper is not surprised.
     */
    static boolean overTheAmount(StoreItemOffer offer) {
        PurchaseQuantity quantity = offer.purchaseQuantity();
        return quantity != null
                && quantity.targetAmount() != null
                && quantity.extraAmount() != null
                && quantity.targetAmount().signum() > 0
                && !"piece".equals(quantity.baseUnit())
                && quantity.extraAmount().compareTo(
                        quantity.targetAmount().multiply(new BigDecimal("0.25"))) > 0;
    }

    private static BigDecimal unitPrice(StoreItemOffer offer) {
        if (!offer.available() || offer.purchaseQuantity() == null) {
            return null;
        }
        BigDecimal supplied = offer.purchaseQuantity().suppliedAmount();
        if (supplied == null || supplied.signum() <= 0) {
            return null;
        }
        return offer.lineTotal().divide(supplied, 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle))
                        .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }
}
