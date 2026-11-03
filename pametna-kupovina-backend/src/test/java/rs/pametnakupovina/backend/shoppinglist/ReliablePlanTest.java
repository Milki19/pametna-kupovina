package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.market.TestMarkets;
import rs.pametnakupovina.backend.routing.RouteMatrix;
import rs.pametnakupovina.backend.routing.RouteMatrixEntry;
import rs.pametnakupovina.backend.routing.RouteMatrixProvider;
import rs.pametnakupovina.backend.store.NearbyStore;
import rs.pametnakupovina.backend.store.NearbyStoreRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules every plan keeps before it is shown (test 1.11.), checked on
 * every scenario of every plan built here.
 */
class ReliablePlanTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 1);
    private static final int SERBIA = TestMarkets.serbia().id();

    private final ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
    private final NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
    private final StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
    private final RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
    private final ShoppingRecommendationService service = new ShoppingRecommendationService(
            listRepository, nearbyRepository, offerRepository, routeProvider,
            new ShoppingOptimizationProperties(), markets());

    /**
     * "prašak za veš Persil 4,5kg" became nine boxes of 561 g discs at
     * 9.531 RSD in Jedna stanica, and the plan wrote "Ušteda 8.529 RSD".
     */
    @Test
    void nineBoxesOfDiscsAreNoFourAndAHalfKilogramsOfPowder() {
        ShoppingListItemResponse powder = item(1L, "prašak za veš Persil 4,5kg");
        ShoppingListItemResponse milk = item(2L, "mleko");
        ShoppingListItemResponse towels = item(3L, "papirni ubrusi");
        NearbyStore near = store(1L, "A");
        NearbyStore maxi = store(2L, "B");
        NearbyStore idea = store(3L, "C");
        NearbyStore lidl = store(4L, "D");
        List<NearbyStore> stores = List.of(near, maxi, idea, lidl);

        // Only the shop with the discs has everything, so one shop was it.
        ShoppingRecommendationResponse response = plan(List.of(powder, milk, towels), stores, List.of(
                packs(offer(near, powder, "1059.00", 101L), 9, "561", "g", "4500"),
                offer(near, milk, "110.00", 102L),
                offer(near, towels, "150.00", 103L),
                packs(offer(maxi, powder, "1499.00", 201L), 1, "4500", "g", "4500"),
                offer(maxi, milk, "130.00", 202L),
                packs(offer(idea, powder, "1549.00", 301L), 1, "4500", "g", "4500"),
                packs(offer(lidl, powder, "1599.00", 401L), 1, "4500", "g", "4500")
        ), TravelMode.DRIVING);

        for (OptimizationScenarioResponse scenario : scenarios(response)) {
            assertThat(scenario.items())
                    .filteredOn(line -> line.itemId() == 1L && line.lineTotal() != null)
                    .allSatisfy(line -> assertThat(line.lineTotal()).isLessThan(new BigDecimal("2000")));
            if (scenario.savingsComparedWithSingleStore() != null) {
                assertThat(scenario.savingsComparedWithSingleStore()).isLessThan(new BigDecimal("500"));
            }
        }
        assertThat(response.singleStore().items())
                .filteredOn(line -> line.itemId() == 1L)
                .singleElement()
                .satisfies(line -> assertThat(java.util.Objects.equals(line.retailerProductId(), 101L)).isFalse());
        assertKeepsTheRules(response);
    }

    /** One pack, but five times what the other shops ask for the same amount. */
    @Test
    void anOfferFarAboveTheUsualPriceIsNoOffer() {
        ShoppingListItemResponse cheese = item(1L, "kačkavalj 500 g");
        ShoppingListItemResponse bread = item(2L, "hleb");
        NearbyStore dear = store(1L, "A");
        List<NearbyStore> stores = new ArrayList<>(List.of(dear));
        List<StoreItemOffer> offers = new ArrayList<>(List.of(
                packs(offer(dear, cheese, "3500.00", 100L), 1, "500", "g", "500"),
                offer(dear, bread, "60.00", 99L)));
        for (long id = 2; id <= 4; id++) {
            NearbyStore other = store(id, "S" + id);
            stores.add(other);
            offers.add(packs(offer(other, cheese, String.valueOf(600 + id * 10), id * 100), 1, "500", "g", "500"));
        }

        ShoppingRecommendationResponse response = plan(List.of(cheese, bread), stores, offers, TravelMode.DRIVING);

        for (OptimizationScenarioResponse scenario : scenarios(response)) {
            assertThat(scenario.items())
                    .filteredOn(line -> line.itemId() == 1L)
                    .singleElement()
                    .satisfies(line -> assertThat(java.util.Objects.equals(line.retailerProductId(), 100L)).isFalse());
        }
        assertKeepsTheRules(response);
    }

    /** Six litres of milk are six packs; that is asked, not absurd. */
    @Test
    void sixLitresOfMilkAreSixPacks() {
        ShoppingListItemResponse milk = item(1L, "mleko 6 l");
        NearbyStore near = store(1L, "A");

        ShoppingRecommendationResponse response = plan(List.of(milk), List.of(near), List.of(
                packs(offer(near, milk, "130.00", 101L), 6, "1000", "ml", "6000")
        ), TravelMode.DRIVING);

        assertThat(response.singleStore().coveredItems()).isEqualTo(1);
        assertThat(response.singleStore().basketCost()).isEqualByComparingTo("780.00");
    }

    /**
     * Test 1.11., "Samo radnje do 1 km": Najjeftinija korpa 3.831 RSD,
     * Najbolje ukupno 3.621 RSD. Najbolje ukupno took an odd cheaper pack
     * Najjeftinija korpa would not.
     */
    @Test
    void theCheapestBasketIsNeverDearerThanAnotherPlanThatCoversAsMuch() {
        ShoppingListItemResponse ketchup = item(1L, "kečap");
        ShoppingListItemResponse bread = item(2L, "hleb");
        NearbyStore metro = store(1L, "A");
        NearbyStore idea = store(2L, "B");

        ShoppingRecommendationResponse response = plan(List.of(ketchup, bread), List.of(metro, idea), List.of(
                odd(offer(metro, ketchup, "45.00", 1001L)),
                offer(metro, bread, "60.00", 1002L),
                offer(idea, ketchup, "100.00", 2001L),
                offer(idea, bread, "70.00", 2002L)
        ), TravelMode.DRIVING);

        assertThat(response.lowestPrice().basketCost()).isEqualByComparingTo("160.00");
        assertThat(response.recommendedBalance().basketCost())
                .isGreaterThanOrEqualTo(response.lowestPrice().basketCost());
        assertKeepsTheRules(response);
    }

    /** "Ušteda" is never zero or less: no saving is shown where there is none. */
    @Test
    void noSavingWhereThereIsNone() {
        ShoppingListItemResponse milk = item(1L, "mleko");
        NearbyStore near = store(1L, "A");

        ShoppingRecommendationResponse response = plan(List.of(milk), List.of(near), List.of(
                offer(near, milk, "120.00", 101L)
        ), TravelMode.DRIVING);

        assertThat(response.recommendedBalance().savingsComparedWithSingleStore()).isNull();
        assertThat(response.lowestPrice().savingsComparedWithSingleStore()).isNull();
    }

    /** 2 × 3 kg for 4,5 kg where no shop has a pack that fits: shown, and said. */
    @Test
    void theNearestPackSaysItIsMoreThanTheList() {
        ShoppingListItemResponse powder = item(1L, "prašak za veš 4,5 kg");
        NearbyStore near = store(1L, "A");

        ShoppingRecommendationResponse response = plan(List.of(powder), List.of(near), List.of(
                packs(offer(near, powder, "900.00", 101L), 2, "3000", "g", "4500")
        ), TravelMode.DRIVING);

        RecommendationItemResponse line = response.singleStore().items().getFirst();
        assertThat(line.resultStatus()).isEqualTo(RecommendationItemStatus.AVAILABLE);
        assertThat(line.substituteNote()).startsWith("Najbliže pakovanje");
    }

    /** Utisak A (31.10.): on foot the plan costs the basket, not dinars for minutes. */
    @Test
    void onFootThePlanCostsOnlyTheBasket() {
        ShoppingListItemResponse milk = item(1L, "mleko");
        ShoppingListItemResponse bread = item(2L, "hleb");
        NearbyStore near = store(1L, "A");
        NearbyStore other = store(2L, "B");

        ShoppingRecommendationResponse response = plan(List.of(milk, bread), List.of(near, other), List.of(
                offer(near, milk, "120.00", 1001L),
                offer(near, bread, "70.00", 1002L),
                offer(other, milk, "110.00", 2001L),
                offer(other, bread, "75.00", 2002L)
        ), TravelMode.WALKING);

        for (OptimizationScenarioResponse scenario : scenarios(response)) {
            assertThat(scenario.travelCost()).isEqualByComparingTo("0");
            assertThat(scenario.timeCost()).isEqualByComparingTo("0");
            assertThat(scenario.stopCost()).isEqualByComparingTo("0");
            assertThat(scenario.totalCost()).isEqualByComparingTo(scenario.basketCost());
            assertThat(scenario.routeDurationSeconds()).isPositive();
        }
        // Still chosen with the minutes and the stops: one shop, not two
        // for 5 RSD.
        assertThat(response.recommendedBalance().stopCount()).isEqualTo(1);
        assertThat(response.recommendedBalance().explanation()).contains("Peške ne računamo");
    }

    /**
     * Utisak B (31.10.): eggs added from Akcije had no shop within walking
     * reach, and the plan on foot sent the shopper to a shop up to 40 km
     * away. On foot it looks only within the walking circle.
     */
    @Test
    void onFootAnItemNoShopNearbyHasIsNotFetchedFromFarAway() {
        ShoppingListItemResponse milk = item(1L, "mleko");
        ShoppingListItemResponse eggs = new ShoppingListItemResponse(
                2L, "jaja", "jaja", null, BigDecimal.ONE,
                ShoppingItemRule.PRODUCT_FAMILY, ShoppingItemMatchingStatus.CONFIRMED,
                null, 777L, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        NearbyStore near = store(1L, "A");

        ShoppingRecommendationResponse response = plan(List.of(milk, eggs), List.of(near), List.of(
                offer(near, milk, "120.00", 1001L)
        ), TravelMode.WALKING);

        verify(nearbyRepository).findNearestCarrying(
                eq(SERBIA), anyDouble(), anyDouble(), eq(List.of(777L)), eq(List.of()), eq(3_000), anyInt());
        assertThat(response.recommendedBalance().items())
                .filteredOn(line -> line.itemId() == 2L)
                .singleElement()
                .satisfies(line -> assertThat(line.explanation()).contains("u krugu hoda (3 km)"));
    }

    /** Driving still looks farther for what no shop nearby has. */
    @Test
    void drivingStillLooksFartherForWhatNoShopNearbyHas() {
        ShoppingListItemResponse eggs = new ShoppingListItemResponse(
                2L, "jaja", "jaja", null, BigDecimal.ONE,
                ShoppingItemRule.PRODUCT_FAMILY, ShoppingItemMatchingStatus.CONFIRMED,
                null, 777L, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        NearbyStore near = store(1L, "A");

        plan(List.of(eggs), List.of(near), List.of(), TravelMode.DRIVING);

        verify(nearbyRepository).findNearestCarrying(
                eq(SERBIA), anyDouble(), anyDouble(), eq(List.of(777L)), eq(List.of()), eq(40_000), anyInt());
    }

    @Test
    void packRuleReadsWhatTheListAsks() {
        ShoppingListItemResponse water = item(1L, "voda");
        NearbyStore near = store(1L, "A");
        StoreItemOffer sixAsked = new StoreItemOffer(near.storeId(), "A", "A", "S", "S", "S", "a", "c", 44, 19,
                1L, "voda", BigDecimal.valueOf(6), ShoppingItemRule.FLEXIBLE_CATEGORY,
                ShoppingItemMatchingStatus.CONFIRMED, 1L, null, "voda 0,5 l", null, null, DATE,
                BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.valueOf(60), "RETAILER",
                new PurchaseQuantity(BigDecimal.valueOf(6), BigDecimal.valueOf(500), "ml",
                        null, BigDecimal.valueOf(3000), null, null));
        assertThat(PlanRules.manyPacks(sixAsked)).isFalse();
        StoreItemOffer sachets = packs(offer(near, water, "16.00", 2L), 13, "16", "g", "200");
        assertThat(PlanRules.manyPacks(sachets)).isTrue();
        // Without anything to compare with, many packs are not held against.
        assertThat(PlanRules.usualUnitPrices(List.of(sachets))).isEmpty();
        assertThat(PlanRules.reasonablePackCount(sachets, java.util.Map.of())).isFalse();
    }

    /**
     * A fish whose amount is unknown at twenty times what fish costs nearby
     * is no fish for the one-stop plan (test 2.11.: "RIBA LIST GRČKA" at
     * 6.599,99 RSD made Super Vero 9.886 RSD against 3.479 RSD for two shops).
     */
    @Test
    void anOfferOfUnknownAmountFarAboveTheUsualLineIsNoChoice() {
        ShoppingListItemResponse fish = item(1L, "riba");
        ShoppingListItemResponse milk = item(2L, "mleko");
        NearbyStore dear = store(1L, "A");
        NearbyStore sprats = store(2L, "B");
        NearbyStore hake = store(3L, "C");
        NearbyStore dairy = store(4L, "D");

        ShoppingRecommendationResponse response = plan(List.of(fish, milk), List.of(dear, sprats, hake, dairy), List.of(
                offer(dear, fish, "6599.99", 101L),
                offer(dear, milk, "100.00", 102L),
                offer(sprats, fish, "339.99", 201L),
                offer(hake, fish, "400.00", 301L),
                offer(dairy, milk, "90.00", 402L)
        ), TravelMode.DRIVING);

        assertThat(java.util.stream.Stream.of(response.singleStore(), response.recommendedBalance(), response.lowestPrice())
                .flatMap(scenario -> scenario.items().stream())
                .filter(line -> line.lineTotal() != null))
                .noneMatch(line -> line.lineTotal().compareTo(new BigDecimal("6599.99")) == 0);
        assertThat(response.singleStore().basketCost()).isLessThan(new BigDecimal("1000"));
    }

    /**
     * Twelve pouches where the line names no amount and a pouch is usual
     * elsewhere: the line says it is a bigger pack, not "scope prioritet"
     * (test 2.11.: 12×85 g cat food at 419,99 RSD against 64,99 RSD).
     */
    @Test
    void aMuchBiggerPackSaysSo() {
        ShoppingListItemResponse cat = item(1L, "hrana za macke");
        ShoppingListItemResponse milk = item(2L, "mleko");
        NearbyStore multipack = store(1L, "A");
        NearbyStore pouchB = store(2L, "B");
        NearbyStore pouchC = store(3L, "C");
        NearbyStore pouchD = store(4L, "D");

        ShoppingRecommendationResponse response = plan(List.of(cat, milk),
                List.of(multipack, pouchB, pouchC, pouchD), List.of(
                        amount(offer(multipack, cat, "419.99", 101L), "1020", "g"),
                        offer(multipack, milk, "89.99", 102L),
                        amount(offer(pouchB, cat, "64.99", 201L), "85", "g"),
                        amount(offer(pouchC, cat, "45.00", 301L), "85", "g"),
                        amount(offer(pouchD, cat, "59.99", 401L), "100", "g")
                ), TravelMode.DRIVING);

        RecommendationItemResponse catLine = response.singleStore().items().stream()
                .filter(line -> line.itemId().equals(1L)).findFirst().orElseThrow();
        assertThat(catLine.storeId()).isEqualTo(1L);
        assertThat(catLine.substituteNote())
                .isEqualTo("Veće pakovanje: 1,02 kg, a obično oko 92,5 g, pa je i cena veća.");
        assertThat(catLine.explanation()).isEqualTo(catLine.substituteNote());
        RecommendationItemResponse milkLine = response.singleStore().items().stream()
                .filter(line -> line.itemId().equals(2L)).findFirst().orElseThrow();
        assertThat(milkLine.substituteNote()).isNull();
        assertThat(milkLine.explanation()).isEqualTo("Cena lanca, ista u njegovim prodavnicama.");
        assertThat(java.util.stream.Stream.of(response.singleStore(), response.recommendedBalance(), response.lowestPrice())
                .flatMap(scenario -> scenario.items().stream()))
                .noneMatch(line -> line.explanation() != null && line.explanation().contains("scope"));
    }

    @Test
    void amountsAreWrittenTheWayAShopperReadsThem() {
        assertThat(ShoppingRecommendationService.amountText(new BigDecimal("85"), "g")).isEqualTo("85 g");
        assertThat(ShoppingRecommendationService.amountText(new BigDecimal("1020"), "g")).isEqualTo("1,02 kg");
        assertThat(ShoppingRecommendationService.amountText(new BigDecimal("1500.000"), "ml")).isEqualTo("1,5 l");
        assertThat(ShoppingRecommendationService.amountText(new BigDecimal("1000"), "g")).isEqualTo("1 kg");
        assertThat(ShoppingRecommendationService.amountText(new BigDecimal("12"), "piece")).isEqualTo("12 kom");
        assertThat(ShoppingRecommendationService.priceSourceText("STORE")).isEqualTo("Cena iz ove prodavnice.");
        assertThat(ShoppingRecommendationService.priceSourceText(null)).isEqualTo("Cena iz cenovnika.");
    }

    /** Five 200 g cups for less than the kilogram tub: many packs, but cheaper. */
    @Test
    void manyCupsThatCostLessThanTheTubAreKept() {
        ShoppingListItemResponse yogurt = item(1L, "jogurt 1 kg");
        NearbyStore cups = store(1L, "A");
        NearbyStore tub = store(2L, "B");
        NearbyStore other = store(3L, "C");

        ShoppingRecommendationResponse response = plan(List.of(yogurt), List.of(cups, tub, other), List.of(
                packs(offer(cups, yogurt, "23.00", 101L), 5, "200", "g", "1000"),
                packs(offer(tub, yogurt, "130.00", 201L), 1, "1000", "g", "1000"),
                packs(offer(other, yogurt, "135.00", 301L), 1, "1000", "g", "1000")
        ), TravelMode.DRIVING);

        assertThat(response.lowestPrice().basketCost()).isEqualByComparingTo("115.00");
    }

    // --- the rules, on every answer ---

    private static void assertKeepsTheRules(ShoppingRecommendationResponse response) {
        OptimizationScenarioResponse lowest = response.lowestPrice();
        for (OptimizationScenarioResponse scenario : scenarios(response)) {
            if (lowest.available() && scenario.available()
                    && scenario.coveredItems() == lowest.coveredItems()
                    && substitutes(scenario) == substitutes(lowest)) {
                assertThat(lowest.basketCost())
                        .as("Najjeftinija korpa ≤ " + scenario.type())
                        .isLessThanOrEqualTo(scenario.basketCost());
            }
            for (RecommendationItemResponse line : scenario.items()) {
                // Nine boxes for one pack never reach a plan.
                if (line.lineTotal() != null && line.purchaseQuantity() != null
                        && line.purchaseQuantity().packages() != null) {
                    assertThat(line.purchaseQuantity().packages())
                            .as("pakovanja za " + line.requestedName())
                            .isLessThan(BigDecimal.valueOf(9));
                }
            }
            if (scenario.savingsComparedWithSingleStore() != null) {
                assertThat(scenario.savingsComparedWithSingleStore()).isPositive();
                assertThat(scenario.coveredItems()).isEqualTo(response.singleStore().coveredItems());
            }
        }
    }

    private static long substitutes(OptimizationScenarioResponse scenario) {
        return scenario.items().stream().filter(line -> line.substituteNote() != null).count();
    }

    private static List<OptimizationScenarioResponse> scenarios(ShoppingRecommendationResponse response) {
        return List.of(response.singleStore(), response.recommendedBalance(), response.lowestPrice());
    }

    // --- building a plan ---

    private ShoppingRecommendationResponse plan(
            List<ShoppingListItemResponse> items,
            List<NearbyStore> stores,
            List<StoreItemOffer> offers,
            TravelMode travelMode
    ) {
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test", OffsetDateTime.now(), OffsetDateTime.now(), items)));
        when(nearbyRepository.findPricingEligibleNearby(eq(SERBIA), eq(44.0), eq(19.0), anyInt(), eq(20)))
                .thenReturn(stores);
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> {
                    List<Long> asked = call.getArgument(1);
                    return offers.stream().filter(offer -> asked.contains(offer.storeId())).toList();
                });
        when(routeProvider.calculate(anyList())).thenAnswer(call -> {
            List<rs.pametnakupovina.backend.routing.RouteWaypoint> waypoints = call.getArgument(0);
            return routeMatrix(waypoints.stream().map(rs.pametnakupovina.backend.routing.RouteWaypoint::id).toList());
        });
        return service.recommend(10L, 44.0, 19.0, DATE, travelMode);
    }

    private static ShoppingListItemResponse item(Long id, String name) {
        return new ShoppingListItemResponse(id, name, name, null, BigDecimal.ONE,
                ShoppingItemRule.FLEXIBLE_CATEGORY, ShoppingItemMatchingStatus.CONFIRMED,
                null, null, null, null, null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static NearbyStore store(Long id, String retailerCode) {
        return new NearbyStore(id, retailerCode, "Retailer " + retailerCode, "STANDARD", "Standard",
                "S-" + id, "Store " + id, "Adresa " + id, "Beograd", 44.0 + id / 100.0, 19.0, id * 300.0);
    }

    private static StoreItemOffer offer(NearbyStore store, ShoppingListItemResponse item, String price, Long productId) {
        BigDecimal value = new BigDecimal(price);
        return new StoreItemOffer(store.storeId(), store.retailerCode(), store.retailerName(),
                store.storeFormatCode(), store.storeFormatName(), store.storeName(), store.address(),
                store.city(), store.latitude(), store.longitude(), item.id(), item.name(), item.quantity(),
                item.matchingRule(), item.matchingStatus(), productId, null, item.name(), "Brend", null,
                DATE, value, null, value, value, "RETAILER");
    }

    /** The same offer bought as {@code packages} packs of {@code size}. */
    private static StoreItemOffer packs(StoreItemOffer o, int packages, String size, String unit, String target) {
        BigDecimal count = BigDecimal.valueOf(packages);
        BigDecimal packSize = new BigDecimal(size);
        BigDecimal supplied = packSize.multiply(count);
        BigDecimal wanted = new BigDecimal(target);
        return new StoreItemOffer(o.storeId(), o.retailerCode(), o.retailerName(), o.storeFormatCode(),
                o.storeFormatName(), o.storeName(), o.address(), o.city(), o.latitude(), o.longitude(),
                o.itemId(), o.requestedName(), o.requestedQuantity(), o.matchingRule(), o.matchingStatus(),
                o.retailerProductId(), o.canonicalProductId(), o.productName(), o.productBrand(),
                o.productBarcode(), o.priceDate(), o.regularPrice(), o.discountedPrice(), o.effectivePrice(),
                o.effectivePrice().multiply(count).setScale(2), o.priceScope(),
                new PurchaseQuantity(count, packSize, unit, wanted, supplied, supplied.subtract(wanted), null),
                true, false);
    }

    /** One pack of {@code size}, for a line that names no amount. */
    private static StoreItemOffer amount(StoreItemOffer o, String size, String unit) {
        BigDecimal packSize = new BigDecimal(size);
        return new StoreItemOffer(o.storeId(), o.retailerCode(), o.retailerName(), o.storeFormatCode(),
                o.storeFormatName(), o.storeName(), o.address(), o.city(), o.latitude(), o.longitude(),
                o.itemId(), o.requestedName(), o.requestedQuantity(), o.matchingRule(), o.matchingStatus(),
                o.retailerProductId(), o.canonicalProductId(), o.productName(), o.productBrand(),
                o.productBarcode(), o.priceDate(), o.regularPrice(), o.discountedPrice(), o.effectivePrice(),
                o.lineTotal(), o.priceScope(),
                new PurchaseQuantity(BigDecimal.ONE, packSize, unit, null, packSize, null, null),
                true, false);
    }

    private static StoreItemOffer odd(StoreItemOffer o) {
        return new StoreItemOffer(o.storeId(), o.retailerCode(), o.retailerName(), o.storeFormatCode(),
                o.storeFormatName(), o.storeName(), o.address(), o.city(), o.latitude(), o.longitude(),
                o.itemId(), o.requestedName(), o.requestedQuantity(), o.matchingRule(), o.matchingStatus(),
                o.retailerProductId(), o.canonicalProductId(), o.productName(), o.productBrand(),
                o.productBarcode(), o.priceDate(), o.regularPrice(), o.discountedPrice(), o.effectivePrice(),
                o.lineTotal(), o.priceScope(), o.purchaseQuantity(), false);
    }

    /** Every shop 300 m a step from home and 600 m from each other. */
    private static RouteMatrix routeMatrix(List<String> ids) {
        List<RouteMatrixEntry> entries = new ArrayList<>();
        for (String origin : ids) {
            for (String destination : ids) {
                long distance = origin.equals(destination) ? 0
                        : origin.equals("USER") || destination.equals("USER") ? 300 * stepOf(origin, destination)
                        : 600;
                entries.add(new RouteMatrixEntry(origin, destination, distance, null));
            }
        }
        return new RouteMatrix("TEST", "STRAIGHT_LINE_ESTIMATE", true, entries);
    }

    private static long stepOf(String origin, String destination) {
        String store = origin.equals("USER") ? destination : origin;
        return Long.parseLong(store.substring("STORE:".length()));
    }

    private static MarketRepository markets() {
        MarketRepository markets = mock(MarketRepository.class);
        when(markets.forPlanningShoppingList(anyLong())).thenReturn(TestMarkets.serbia());
        return markets;
    }
}
