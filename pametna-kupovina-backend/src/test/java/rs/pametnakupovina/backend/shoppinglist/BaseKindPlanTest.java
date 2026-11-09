package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.market.TestMarkets;
import rs.pametnakupovina.backend.routing.RouteMatrix;
import rs.pametnakupovina.backend.routing.RouteMatrixEntry;
import rs.pametnakupovina.backend.routing.RouteMatrixProvider;
import rs.pametnakupovina.backend.routing.RouteWaypoint;
import rs.pametnakupovina.backend.store.NearbyStore;
import rs.pametnakupovina.backend.store.NearbyStoreRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A line that names only a base kind is judged against what the whole kind
 * costs in the market (test 6.11.: "riba 4" was four sea bass at 2.504 RSD/kg,
 * 10.016 RSD, in Jedna stanica, because the fish nearby were all dear).
 */
class BaseKindPlanTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 6);
    private static final int SERBIA = TestMarkets.serbia().id();
    private static final long FISH = 77L;

    private final ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
    private final NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
    private final StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
    private final RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);

    /** Fish usually costs 1.000 RSD a kilogram; only item 1 is a plain "riba". */
    private final BaseKindPrices prices = new BaseKindPrices() {
        @Override
        public Map<Long, PlainKind> plainLines(long listId) {
            return Map.of(1L, new PlainKind(FISH, "Sveža riba"));
        }

        @Override
        public Optional<BigDecimal> usualPerThousand(Market market, long baseKindId, String baseUnit) {
            return baseKindId == FISH && "g".equals(baseUnit)
                    ? Optional.of(new BigDecimal("1000.00"))
                    : Optional.empty();
        }

        @Override
        public Map<Long, PackAmount> packAmounts(Collection<Long> retailerProductIds) {
            // Product 501 is sold by the kilogram, its amount unknown to the plan.
            return retailerProductIds.contains(501L)
                    ? Map.of(501L, new PackAmount(new BigDecimal("1000"), "g"))
                    : Map.of();
        }
    };

    private final ShoppingRecommendationService service = new ShoppingRecommendationService(
            listRepository, nearbyRepository, offerRepository, routeProvider,
            new ShoppingOptimizationProperties(), markets(), prices);

    @Test
    void aPlainKindTakesNothingFarAboveWhatTheKindUsuallyCosts() {
        ShoppingListItemResponse fish = item(1L, "riba", "4");
        ShoppingListItemResponse milk = item(2L, "mleko", "1");
        NearbyStore seaBass = store(1L, "A");
        NearbyStore salmon = store(2L, "B");
        NearbyStore bream = store(3L, "C");

        ShoppingRecommendationResponse response = plan(List.of(fish, milk), List.of(seaBass, salmon, bream), List.of(
                kilos(offer(seaBass, fish, "Brancin svež", "2504.00", 101L), 4),
                offer(seaBass, milk, "Mleko", "120.00", 102L),
                kilos(offer(salmon, fish, "Losos svež", "2900.00", 201L), 4),
                kilos(offer(bream, fish, "Orada sveža", "2300.00", 301L), 4)
        ));

        assertThat(allLines(response).filter(line -> line.lineTotal() != null))
                .noneMatch(line -> line.lineTotal().compareTo(new BigDecimal("10016.00")) == 0);
        RecommendationItemResponse oneStopFish = response.singleStore().items().stream()
                .filter(line -> line.itemId().equals(1L))
                .findFirst().orElseThrow();
        assertThat(oneStopFish.lineTotal()).isNull();
        assertThat(oneStopFish.explanation())
                .contains("Ovde nema obične vrste za „riba“")
                .contains("2504 RSD/kg")
                .contains("sveža riba obično košta oko 1000 RSD/kg");
    }

    @Test
    void anOrdinaryFishOfTheKindStaysTheChoice() {
        ShoppingListItemResponse fish = item(1L, "riba", "1");
        NearbyStore seaBass = store(1L, "A");
        NearbyStore mackerel = store(2L, "B");

        ShoppingRecommendationResponse response = plan(List.of(fish), List.of(seaBass, mackerel), List.of(
                kilos(offer(seaBass, fish, "Brancin svež", "2504.00", 101L), 1),
                kilos(offer(mackerel, fish, "Skuša sveža", "699.99", 201L), 1)
        ));

        assertThat(response.singleStore().items().get(0).lineTotal()).isEqualByComparingTo("699.99");
        assertThat(allLines(response)).noneMatch(line -> "Brancin svež".equals(line.productName()));
    }

    /** A fish sold by the kilogram whose amount the plan does not know is read per kilogram too. */
    @Test
    void aFishSoldByTheKilogramIsReadPerKilogram() {
        ShoppingListItemResponse fish = item(1L, "riba", "1");
        NearbyStore seaBass = store(1L, "A");

        ShoppingRecommendationResponse response = plan(List.of(fish), List.of(seaBass), List.of(
                offer(seaBass, fish, "Brancin svež kg", "2504.00", 501L)
        ));

        assertThat(response.singleStore().items().get(0).lineTotal()).isNull();
    }

    /** "brancin" asks for sea bass: the line names more than the kind and keeps it. */
    @Test
    void aLineThatNamesTheFishKeepsIt() {
        ShoppingListItemResponse seaBassLine = item(2L, "brancin", "1");
        NearbyStore seaBass = store(1L, "A");

        ShoppingRecommendationResponse response = plan(List.of(seaBassLine), List.of(seaBass), List.of(
                kilos(offer(seaBass, seaBassLine, "Brancin svež", "2504.00", 101L), 1)
        ));

        assertThat(response.singleStore().items().get(0).lineTotal()).isEqualByComparingTo("2504.00");
    }

    @Test
    void theRuleReadsAKilogramOrALitre() {
        ShoppingListItemResponse fish = item(1L, "riba", "1");
        NearbyStore store = store(1L, "A");
        PlanRules.KindLimit limit = new PlanRules.KindLimit("Sveža riba",
                Map.of("g", new BigDecimal("1000.00")), "RSD");

        assertThat(PlanRules.withinBaseKind(kilos(offer(store, fish, "Oslić", "1999.99", 1L), 1), limit, null))
                .isTrue();
        assertThat(PlanRules.withinBaseKind(kilos(offer(store, fish, "Brancin", "2504.00", 1L), 1), limit, null))
                .isFalse();
        // Half a kilogram for 1.100 RSD is 2.200 RSD a kilogram.
        assertThat(PlanRules.withinBaseKind(grams(offer(store, fish, "Losos 500 g", "1100.00", 1L), "500"), limit, null))
                .isFalse();
        // Nothing to judge by: no amount, or no usual price for litres.
        assertThat(PlanRules.withinBaseKind(offer(store, fish, "Riba", "9999.00", 1L), limit, null)).isTrue();
        assertThat(PlanRules.withinBaseKind(grams(offer(store, fish, "Riblje ulje", "9999.00", 1L), "1000", "ml"),
                limit, null)).isTrue();
        assertThat(PlanRules.withinBaseKind(kilos(offer(store, fish, "Brancin", "2504.00", 1L), 1), null, null))
                .isTrue();
    }

    // --- building a plan ---

    private ShoppingRecommendationResponse plan(
            List<ShoppingListItemResponse> items,
            List<NearbyStore> stores,
            List<StoreItemOffer> offers
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
            List<RouteWaypoint> waypoints = call.getArgument(0);
            return routeMatrix(waypoints.stream().map(RouteWaypoint::id).toList());
        });
        return service.recommend(10L, 44.0, 19.0, DATE, TravelMode.DRIVING);
    }

    private static Stream<RecommendationItemResponse> allLines(ShoppingRecommendationResponse response) {
        return Stream.of(response.singleStore(), response.recommendedBalance(), response.lowestPrice())
                .flatMap(scenario -> scenario.items().stream());
    }

    private static ShoppingListItemResponse item(Long id, String name, String quantity) {
        return new ShoppingListItemResponse(id, name, name, null, new BigDecimal(quantity),
                ShoppingItemRule.FLEXIBLE_CATEGORY, ShoppingItemMatchingStatus.CONFIRMED,
                null, null, null, null, null, null, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static NearbyStore store(Long id, String retailerCode) {
        return new NearbyStore(id, retailerCode, "Retailer " + retailerCode, "STANDARD", "Standard",
                "S-" + id, "Store " + id, "Adresa " + id, "Beograd", 44.0 + id / 100.0, 19.0, id * 300.0);
    }

    private static StoreItemOffer offer(NearbyStore store, ShoppingListItemResponse item, String product,
                                        String price, Long productId) {
        BigDecimal value = new BigDecimal(price);
        return new StoreItemOffer(store.storeId(), store.retailerCode(), store.retailerName(),
                store.storeFormatCode(), store.storeFormatName(), store.storeName(), store.address(),
                store.city(), store.latitude(), store.longitude(), item.id(), item.name(), item.quantity(),
                item.matchingRule(), item.matchingStatus(), productId, null, product, "Brend", null,
                DATE, value, null, value, value, "RETAILER");
    }

    /** {@code count} packs of one kilogram, as "riba 4" bought four sea bass. */
    private static StoreItemOffer kilos(StoreItemOffer o, int count) {
        BigDecimal packs = BigDecimal.valueOf(count);
        BigDecimal supplied = new BigDecimal("1000").multiply(packs);
        return with(o, o.effectivePrice().multiply(packs).setScale(2),
                new PurchaseQuantity(packs, new BigDecimal("1000"), "g", null, supplied, null, null));
    }

    private static StoreItemOffer grams(StoreItemOffer o, String size) {
        return grams(o, size, "g");
    }

    private static StoreItemOffer grams(StoreItemOffer o, String size, String unit) {
        BigDecimal amount = new BigDecimal(size);
        return with(o, o.lineTotal(),
                new PurchaseQuantity(BigDecimal.ONE, amount, unit, null, amount, null, null));
    }

    private static StoreItemOffer with(StoreItemOffer o, BigDecimal lineTotal, PurchaseQuantity quantity) {
        return new StoreItemOffer(o.storeId(), o.retailerCode(), o.retailerName(), o.storeFormatCode(),
                o.storeFormatName(), o.storeName(), o.address(), o.city(), o.latitude(), o.longitude(),
                o.itemId(), o.requestedName(), o.requestedQuantity(), o.matchingRule(), o.matchingStatus(),
                o.retailerProductId(), o.canonicalProductId(), o.productName(), o.productBrand(),
                o.productBarcode(), o.priceDate(), o.regularPrice(), o.discountedPrice(), o.effectivePrice(),
                lineTotal, o.priceScope(), quantity, true, false);
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
