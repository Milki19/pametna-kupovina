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

    /**
     * What a line for the item usually costs: the median of what the shops
     * of this plan ask for it. It judges an offer whose amount is not known
     * (test 2.11.: "RIBA LIST GRČKA" at 6.599,99 RSD, a kilogram or a box,
     * was "riba" in the one-stop plan where fish elsewhere cost 340 RSD).
     */
    static Map<Long, BigDecimal> usualLinePrices(Collection<StoreItemOffer> offers) {
        Map<Long, List<BigDecimal>> byItem = new HashMap<>();
        for (StoreItemOffer offer : offers) {
            if (offer.available() && offer.lineTotal() != null && offer.lineTotal().signum() > 0) {
                byItem.computeIfAbsent(offer.itemId(), id -> new java.util.ArrayList<>()).add(offer.lineTotal());
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

    /**
     * No more than three times what the item usually costs: a gram, a
     * millilitre or a piece where the amount is known, the whole line
     * where it is not.
     */
    static boolean usualPrice(
            StoreItemOffer offer,
            Map<Long, BigDecimal> usualUnitPrices,
            Map<Long, BigDecimal> usualLinePrices
    ) {
        BigDecimal unitPrice = unitPrice(offer);
        if (unitPrice == null) {
            BigDecimal usualLine = usualLinePrices.get(offer.itemId());
            return usualLine == null
                    || offer.lineTotal() == null
                    || offer.lineTotal().compareTo(usualLine.multiply(MAX_TIMES_MEDIAN)) <= 0;
        }
        BigDecimal usual = usualUnitPrices.get(offer.itemId());
        return usual == null || unitPrice.compareTo(usual.multiply(MAX_TIMES_MEDIAN)) <= 0;
    }

    static boolean passes(
            StoreItemOffer offer,
            Map<Long, BigDecimal> usualUnitPrices,
            Map<Long, BigDecimal> usualLinePrices
    ) {
        return !offer.available()
                || (reasonablePackCount(offer, usualUnitPrices)
                        && usualPrice(offer, usualUnitPrices, usualLinePrices));
    }

    /** The offers a plan may count on; the rest are as if the shop had none. */
    static List<StoreItemOffer> keep(
            List<StoreItemOffer> offers,
            Map<Long, BigDecimal> usualUnitPrices,
            Map<Long, BigDecimal> usualLinePrices
    ) {
        return offers.stream()
                .filter(offer -> passes(offer, usualUnitPrices, usualLinePrices))
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

    /** A pack at least this many times the usual amount is called bigger. */
    static final BigDecimal BIGGER_PACK_TIMES = BigDecimal.valueOf(2);

    /**
     * How much of the item a line usually brings, per unit (g, ml, piece):
     * the median of what the shops of this plan offer for it. Items with too
     * few offers in one unit have none.
     */
    static Map<String, BigDecimal> usualAmounts(Collection<StoreItemOffer> offers) {
        Map<String, List<BigDecimal>> byItem = new HashMap<>();
        for (StoreItemOffer offer : offers) {
            PurchaseQuantity quantity = offer.purchaseQuantity();
            if (offer.available() && quantity != null && quantity.baseUnit() != null
                    && quantity.suppliedAmount() != null && quantity.suppliedAmount().signum() > 0) {
                byItem.computeIfAbsent(amountKey(offer.itemId(), quantity.baseUnit()),
                        key -> new java.util.ArrayList<>()).add(quantity.suppliedAmount());
            }
        }
        Map<String, BigDecimal> usual = new HashMap<>();
        byItem.forEach((key, amounts) -> {
            if (amounts.size() >= MIN_OFFERS_FOR_MEDIAN) {
                usual.put(key, median(amounts));
            }
        });
        return usual;
    }

    /**
     * The usual amount, where the line names none and this pack is at least
     * twice it (test 2.11.: "hrana za mačke" was 12×85 g at 419,99 RSD in
     * the one-stop plan, a single 85 g pouch at 64,99 RSD elsewhere, and the
     * line said only that the price was chosen by scope). Null otherwise.
     */
    static BigDecimal usualAmountOfBiggerPack(StoreItemOffer offer, Map<String, BigDecimal> usualAmounts) {
        PurchaseQuantity quantity = offer.purchaseQuantity();
        if (!offer.available() || quantity == null || quantity.baseUnit() == null
                || quantity.suppliedAmount() == null
                || (quantity.targetAmount() != null && quantity.targetAmount().signum() > 0)) {
            return null;
        }
        BigDecimal usual = usualAmounts.get(amountKey(offer.itemId(), quantity.baseUnit()));
        return usual != null && usual.signum() > 0
                && quantity.suppliedAmount().compareTo(usual.multiply(BIGGER_PACK_TIMES)) >= 0
                ? usual
                : null;
    }

    private static String amountKey(Long itemId, String baseUnit) {
        return itemId + ":" + baseUnit;
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
