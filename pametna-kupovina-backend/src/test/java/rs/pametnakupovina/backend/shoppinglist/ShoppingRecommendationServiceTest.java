package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingRecommendationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 5);
    private static final int SERBIA = TestMarkets.serbia().id();

    @Test
    void returnsSingleBalancedAndExactTwoStoreMinimum() {
        ShoppingListRepository listRepository =
                mock(ShoppingListRepository.class);

        NearbyStoreRepository nearbyRepository =
                mock(NearbyStoreRepository.class);

        StoreShoppingOfferRepository offerRepository =
                mock(StoreShoppingOfferRepository.class);

        RouteMatrixProvider routeProvider =
                mock(RouteMatrixProvider.class);

        ShoppingOptimizationProperties properties =
                new ShoppingOptimizationProperties();

        ShoppingRecommendationService service =
                new ShoppingRecommendationService(
                        listRepository,
                        nearbyRepository,
                        offerRepository,
                        routeProvider,
                        properties,
                        markets()
                );

        ShoppingListItemResponse firstItem = item(
                1L,
                "Mleko",
                101L
        );

        ShoppingListItemResponse secondItem = item(
                2L,
                "Hleb",
                102L
        );

        ShoppingListResponse shoppingList = new ShoppingListResponse(
                10L,
                "Test korpa",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(firstItem, secondItem)
        );

        NearbyStore firstStore = store(1L, "A");
        NearbyStore secondStore = store(2L, "B");

        when(listRepository.findById(10L))
                .thenReturn(Optional.of(shoppingList));

        when(nearbyRepository.findPricingEligibleNearby(
                SERBIA,
                44.0,
                19.0,
                15_000,
                20
        )).thenReturn(List.of(firstStore, secondStore));

        when(offerRepository.findOffers(
                eq(10L),
                anyList(),
                eq(DATE),
                eq(true)
        )).thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                offer(firstStore, firstItem, 100, 1001L),
                offer(firstStore, secondItem, 300, 1002L),
                offer(secondStore, firstItem, 130, 2001L),
                offer(secondStore, secondItem, 100, 2002L)
        )));

        when(routeProvider.calculate(anyList()))
                .thenReturn(routeMatrix());

        ShoppingRecommendationResponse response = service.recommend(
                10L,
                44.0,
                19.0,
                DATE
        );

        assertThat(response.evaluatedSingleStoreScenarios())
                .isEqualTo(2);
        assertThat(response.evaluatedTwoStoreCombinations())
                .isEqualTo(1);

        assertThat(response.singleStore().basketCost())
                .isEqualByComparingTo("230.00");
        assertThat(response.singleStore().stopCount()).isEqualTo(1);

        assertThat(response.recommendedBalance().basketCost())
                .isEqualByComparingTo("230.00");
        assertThat(response.recommendedBalance().stopCount())
                .isEqualTo(1);

        assertThat(response.lowestPrice().basketCost())
                .isEqualByComparingTo("200.00");
        assertThat(response.lowestPrice().stopCount()).isEqualTo(2);
        assertThat(response.lowestPrice().items())
                .hasSize(2)
                .allMatch(item -> item.resultStatus()
                        == RecommendationItemStatus.AVAILABLE);

        assertThat(response.lowestPrice().priceSources())
                .containsExactlyInAnyOrder("A", "B");
        assertThat(response.disclaimer())
                .contains("nisu garantovane");
    }

    /**
     * Peti test, 1.11.2026: five people asking for the same plan at once
     * were answered after 1, 2, 3, 4 and 5 s. The plan is worked out once
     * and everyone gets it; asked again afterwards, it is worked out anew.
     */
    @Test
    void theSamePlanAskedFiveTimesAtOnceIsWorkedOutOnce() throws Exception {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets());

        ShoppingListItemResponse milk = item(1L, "Mleko", 101L);
        ShoppingListResponse shoppingList = new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(milk));
        NearbyStore shop = store(1L, "A");
        when(listRepository.findById(10L)).thenReturn(Optional.of(shoppingList));

        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger plansWorkedOut = new java.util.concurrent.atomic.AtomicInteger();
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 15_000, 20))
                .thenAnswer(call -> {
                    plansWorkedOut.incrementAndGet();
                    release.await();
                    return List.of(shop);
                });
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(offer(shop, milk, 100, 1001L))));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix());

        List<Thread> people = new ArrayList<>();
        List<ShoppingRecommendationResponse> answers =
                java.util.Collections.synchronizedList(new ArrayList<>());
        for (int person = 0; person < 5; person++) {
            Thread asking = new Thread(() -> answers.add(service.recommend(10L, 44.0, 19.0, DATE)));
            people.add(asking);
            asking.start();
        }
        // One is working the plan out (held at the shops), four wait for it.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!people.stream().allMatch(asking -> asking.getState() == Thread.State.WAITING)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        release.countDown();
        for (Thread asking : people) {
            asking.join(10_000);
        }

        assertThat(answers).hasSize(5);
        assertThat(answers).allMatch(answer -> answer == answers.getFirst());
        assertThat(plansWorkedOut).hasValue(1);

        service.recommend(10L, 44.0, 19.0, DATE);
        assertThat(plansWorkedOut).hasValue(2);
    }

    @Test
    void chainsWithoutAddressesCanBeAskedForApartFromThePlan() {
        ShoppingListRepository listRepository =
                mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository =
                mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository =
                mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider =
                mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service =
                new ShoppingRecommendationService(
                        listRepository,
                        nearbyRepository,
                        offerRepository,
                        routeProvider,
                        new ShoppingOptimizationProperties(),
                        markets()
                );

        ShoppingListItemResponse milk = item(1L, "Mleko", 101L);
        ShoppingListItemResponse bread = item(2L, "Hleb", 102L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L,
                "Test korpa",
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(milk, bread)
        )));

        NearbyStore nearby = store(1L, "A");
        // A published list whose shop has no address.
        NearbyStore priceList = store(3L, "C");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 15_000, 20))
                .thenReturn(List.of(nearby));
        when(offerRepository.findPriceListEntriesWithoutLocation(SERBIA))
                .thenReturn(List.of(new StoreShoppingOfferRepository.PriceListEntry(
                        3L, "C", "Lanac C", "Cenovnik C"
                )));
        List<List<Long>> askedFor = new ArrayList<>();
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> {
                    askedFor.add(List.copyOf(call.getArgument(1)));
                    return offersFor(call.getArgument(1), List.of(
                            offer(nearby, milk, 100, 1001L),
                            offer(nearby, bread, 300, 1002L),
                            offer(priceList, milk, 90, 3001L),
                            offer(priceList, bread, 80, 3002L)
                    ));
                });
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix(List.of(1L)));

        ShoppingRecommendationResponse together =
                service.recommend(10L, 44.0, 19.0, DATE);
        ShoppingRecommendationResponse planOnly = service.recommend(
                10L, 44.0, 19.0, DATE, TravelMode.DRIVING, false);
        askedFor.clear();
        List<UnlocatedPriceOption> apart = service.unlocatedPriceOptions(10L, DATE);

        assertThat(together.unlocatedPriceOptions()).hasSize(1);
        assertThat(together.unlocatedPriceOptions().getFirst().lowestBasketCost())
                .isEqualByComparingTo("170.00");
        assertThat(planOnly.unlocatedPriceOptions()).isEmpty();
        assertThat(planOnly.recommendedBalance()).isEqualTo(together.recommendedBalance());
        assertThat(planOnly.singleStore()).isEqualTo(together.singleStore());
        assertThat(planOnly.lowestPrice()).isEqualTo(together.lowestPrice());
        assertThat(apart).isEqualTo(together.unlocatedPriceOptions());
        // On their own, the nearby shops are not priced again.
        assertThat(askedFor).flatExtracting(ids -> ids).containsExactly(3L);
    }

    @Test
    void singleStoreCountsTheTripNotOnlyTheBasket() {
        ShoppingListRepository listRepository =
                mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository =
                mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository =
                mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider =
                mock(RouteMatrixProvider.class);

        ShoppingRecommendationService service =
                new ShoppingRecommendationService(
                        listRepository,
                        nearbyRepository,
                        offerRepository,
                        routeProvider,
                        new ShoppingOptimizationProperties(),
                        markets()
                );

        ShoppingListItemResponse milk = item(1L, "Mleko", 101L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(
                new ShoppingListResponse(
                        10L,
                        "Test korpa",
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        List.of(milk)
                )
        ));

        // Seen on 17.10.: the shop round the corner at 225 RSD, and one
        // 12.9 km away a little cheaper. One stop is the shop round the corner.
        NearbyStore nearStore = store(1L, "A");
        NearbyStore farStore = store(2L, "B");
        when(nearbyRepository.findPricingEligibleNearby(
                SERBIA, 44.0, 19.0, 15_000, 20
        )).thenReturn(List.of(nearStore, farStore));
        when(offerRepository.findOffers(
                eq(10L), anyList(), eq(DATE), eq(true)
        )).thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                offer(nearStore, milk, 222, 1001L),
                offer(farStore, milk, 200, 2001L)
        )));

        List<RouteMatrixEntry> entries = new ArrayList<>();
        long[][] meters = {{0, 125, 6_450}, {125, 0, 6_400}, {6_450, 6_400, 0}};
        String[] ids = {"USER", "STORE:1", "STORE:2"};
        for (int from = 0; from < ids.length; from++) {
            for (int to = 0; to < ids.length; to++) {
                entries.add(new RouteMatrixEntry(
                        ids[from], ids[to], meters[from][to], null));
            }
        }
        when(routeProvider.calculate(anyList())).thenReturn(new RouteMatrix(
                "TEST", "STRAIGHT_LINE_ESTIMATE", true, entries));

        ShoppingRecommendationResponse response =
                service.recommend(10L, 44.0, 19.0, DATE);

        assertThat(response.singleStore().stores())
                .extracting(RecommendationStoreResponse::storeId)
                .containsExactly(1L);
        assertThat(response.singleStore().totalCost())
                .isEqualByComparingTo(response.recommendedBalance().totalCost());
        // The cheapest basket stays where it belongs, in its own plan.
        assertThat(response.lowestPrice().basketCost())
                .isEqualByComparingTo("200.00");
    }

    @Test
    void oneStopMissesWhatFewShopsSellRatherThanWhatEveryShopHas() {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets());

        ShoppingListItemResponse onion = item(1L, "Luk", 101L);
        ShoppingListItemResponse catFood = item(2L, "Hrana za mačke", 102L);
        ShoppingListItemResponse chips = item(3L, "Tortilja čips", 103L);
        ShoppingListItemResponse salami = item(4L, "Parmezan salama", 104L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(),
                List.of(onion, catFood, chips, salami))));

        // Seen on the copy 1.11.: two shops miss two items each, one onions
        // and cat food, which every shop has, the other two rare ones.
        NearbyStore rare = store(1L, "A");
        NearbyStore usual = store(2L, "B");
        NearbyStore other = store(3L, "C");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 15_000, 20))
                .thenReturn(List.of(rare, usual, other));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                        offer(rare, chips, 100, 1003L),
                        offer(rare, salami, 100, 1004L),
                        offer(usual, onion, 110, 2001L),
                        offer(usual, catFood, 110, 2002L),
                        offer(other, onion, 130, 3001L),
                        offer(other, catFood, 130, 3002L)
                )));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix(List.of(1L, 2L, 3L)));

        ShoppingRecommendationResponse response = service.recommend(10L, 44.0, 19.0, DATE);

        assertThat(response.singleStore().stores())
                .extracting(RecommendationStoreResponse::storeId)
                .containsExactly(2L);
    }

    @Test
    void importantExactCandidateBlocksOptimizationUntilConfirmed() {
        ShoppingListRepository listRepository =
                mock(ShoppingListRepository.class);

        ShoppingListItemResponse pending = new ShoppingListItemResponse(
                5L,
                "Mleko",
                "Mleko",
                null,
                BigDecimal.ONE,
                ShoppingItemRule.EXACT_PRODUCT,
                ShoppingItemMatchingStatus.NEEDS_CONFIRMATION,
                null,
                50L,
                new BigDecimal("0.8000"),
                "test-v1",
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        when(listRepository.findById(11L)).thenReturn(Optional.of(
                new ShoppingListResponse(
                        11L,
                        "Blokirana korpa",
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        List.of(pending)
                )
        ));

        ShoppingRecommendationService service =
                new ShoppingRecommendationService(
                        listRepository,
                        mock(NearbyStoreRepository.class),
                        mock(StoreShoppingOfferRepository.class),
                        mock(RouteMatrixProvider.class),
                        new ShoppingOptimizationProperties(),
                        markets()
                );

        assertThatThrownBy(() -> service.recommend(
                11L,
                44.0,
                19.0,
                DATE
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Potvrdite važne stavke")
                .hasMessageContaining("5");
    }

    @Test
    void keepsUnmatchedSeparateFromItemWithoutValidPrice() {
        ShoppingListRepository listRepository =
                mock(ShoppingListRepository.class);

        NearbyStoreRepository nearbyRepository =
                mock(NearbyStoreRepository.class);

        StoreShoppingOfferRepository offerRepository =
                mock(StoreShoppingOfferRepository.class);

        RouteMatrixProvider routeProvider =
                mock(RouteMatrixProvider.class);

        ShoppingListItemResponse unmatched =
                new ShoppingListItemResponse(
                        6L,
                        "Nepoznat proizvod",
                        "Nepoznat proizvod",
                        null,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT,
                        ShoppingItemMatchingStatus.UNMATCHED,
                        null,
                        60L,
                        new BigDecimal("0.4000"),
                        "test-v1",
                        null,
                        OffsetDateTime.now(),
                        OffsetDateTime.now()
                );

        ShoppingListItemResponse withoutPrice = item(
                7L,
                "Poznat proizvod bez cene",
                107L
        );

        NearbyStore store = store(1L, "A");

        when(listRepository.findById(12L)).thenReturn(Optional.of(
                new ShoppingListResponse(
                        12L,
                        "Nepotpuna korpa",
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        List.of(unmatched, withoutPrice)
                )
        ));

        when(nearbyRepository.findPricingEligibleNearby(
                SERBIA,
                44.0,
                19.0,
                15_000,
                20
        )).thenReturn(List.of(store));

        when(offerRepository.findOffers(
                12L,
                List.of(1L),
                DATE,
                true
        )).thenReturn(List.of());

        when(routeProvider.calculate(anyList()))
                .thenReturn(routeMatrix());

        ShoppingRecommendationResponse response =
                new ShoppingRecommendationService(
                        listRepository,
                        nearbyRepository,
                        offerRepository,
                        routeProvider,
                        new ShoppingOptimizationProperties(),
                        markets()
                ).recommend(12L, 44.0, 19.0, DATE);

        assertThat(response.recommendedBalance().available())
                .isFalse();
        assertThat(response.recommendedBalance().stopCount())
                .isZero();
        assertThat(response.recommendedBalance().items())
                .extracting(
                        RecommendationItemResponse::itemId,
                        RecommendationItemResponse::resultStatus
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                6L,
                                RecommendationItemStatus.UNMATCHED
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                7L,
                                RecommendationItemStatus.NO_VALID_PRICE
                        )
                );
    }

    @Test
    void freshOfferWinsOverCheaperStaleOffer() {
        StoreItemOffer fresh = datedOffer(1L, 10L, 120, DATE.minusDays(2));
        StoreItemOffer stale = datedOffer(2L, 10L, 20, DATE.minusDays(60));

        assertThat(ShoppingRecommendationService.preferFreshOffers(
                List.of(stale, fresh),
                DATE,
                30
        )).containsExactly(fresh);
    }

    @Test
    void staleOfferRemainsWhenNoFreshPriceExistsForItem() {
        StoreItemOffer stale = datedOffer(1L, 10L, 20, DATE.minusDays(60));

        assertThat(ShoppingRecommendationService.preferFreshOffers(
                List.of(stale),
                DATE,
                30
        )).containsExactly(stale);
    }

    @Test
    void completePlanIsNotComparedWithIncompleteSingleStorePlan() {
        ShoppingListRepository listRepository =
                mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository =
                mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository =
                mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider =
                mock(RouteMatrixProvider.class);

        ShoppingListItemResponse firstItem = item(21L, "Mleko", 201L);
        ShoppingListItemResponse secondItem = item(22L, "Sok", 202L);
        NearbyStore firstStore = store(1L, "A");
        NearbyStore secondStore = store(2L, "B");

        when(listRepository.findById(20L)).thenReturn(Optional.of(
                new ShoppingListResponse(
                        20L,
                        "Podeljena korpa",
                        OffsetDateTime.now(),
                        OffsetDateTime.now(),
                        List.of(firstItem, secondItem)
                )
        ));
        when(nearbyRepository.findPricingEligibleNearby(
                SERBIA,
                44.0,
                19.0,
                15_000,
                20
        )).thenReturn(List.of(firstStore, secondStore));
        when(offerRepository.findOffers(
                eq(20L),
                anyList(),
                eq(DATE),
                eq(true)
        )).thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                offer(firstStore, firstItem, 100, 2101L),
                offer(secondStore, secondItem, 50, 2201L)
        )));
        when(routeProvider.calculate(anyList()))
                .thenReturn(routeMatrix());

        ShoppingRecommendationResponse response =
                new ShoppingRecommendationService(
                        listRepository,
                        nearbyRepository,
                        offerRepository,
                        routeProvider,
                        new ShoppingOptimizationProperties(),
                        markets()
                ).recommend(20L, 44.0, 19.0, DATE);

        assertThat(response.singleStore().complete()).isFalse();
        assertThat(response.singleStore().coveredItems()).isEqualTo(1);
        assertThat(response.singleStore().items())
                .filteredOn(item -> item.resultStatus()
                        == RecommendationItemStatus.NO_VALID_PRICE)
                .singleElement()
                .extracting(RecommendationItemResponse::explanation)
                .asString()
                .contains("u drugom planu");

        assertThat(response.recommendedBalance().complete()).isTrue();
        assertThat(response.recommendedBalance().coveredItems())
                .isEqualTo(2);
        assertThat(response.recommendedBalance()
                .savingsComparedWithSingleStore()).isNull();
    }

    /**
     * Tata je izabrao Perutninu, a nijedna radnja u krugu je ne prodaje: plan
     * je onda traži dalje, u najbližoj radnji lanca koji je ima, umesto da
     * kaže da je nema nigde.
     */
    @Test
    void aProductNoNearbyShopSellsIsLookedForFartherAway() {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets());

        ShoppingListItemResponse milk = item(1L, "Mleko", 101L);
        ShoppingListItemResponse breast = new ShoppingListItemResponse(
                2L, "Pileća prsa", "Pileća prsa", null, BigDecimal.ONE,
                ShoppingItemRule.PRODUCT_FAMILY, ShoppingItemMatchingStatus.CONFIRMED,
                null, 555L, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(),
                List.of(milk, breast))));

        NearbyStore near = store(1L, "A");
        NearbyStore far = store(3L, "C");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 15_000, 20))
                .thenReturn(List.of(near));
        when(nearbyRepository.findNearestCarrying(SERBIA, 44.0, 19.0, List.of(555L), List.of(), 40_000, 3))
                .thenReturn(List.of(far));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                        offer(near, milk, 100, 1001L),
                        offer(far, breast, 300, 3002L))));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix(List.of(1L, 3L)));

        ShoppingRecommendationResponse response = service.recommend(10L, 44.0, 19.0, DATE);

        assertThat(response.recommendedBalance().stores())
                .extracting(store -> store.storeId())
                .contains(3L);
        assertThat(response.recommendedBalance().items())
                .filteredOn(line -> line.itemId() == 2L)
                .singleElement()
                .satisfies(line -> assertThat(line.resultStatus())
                        .isEqualTo(RecommendationItemStatus.AVAILABLE));
    }

    /** Peške: radnje u krugu od 3 km, kilometar ne košta, a vreme je hodom. */
    @Test
    void onFootTheTripCostsTimeButNoKilometres() {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets());

        ShoppingListItemResponse milk = item(1L, "Mleko", 101L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(milk))));
        NearbyStore near = store(1L, "A");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 3_000, 20))
                .thenReturn(List.of(near));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(offer(near, milk, 100, 1001L))));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix(List.of(1L)));

        ShoppingRecommendationResponse response =
                service.recommend(10L, 44.0, 19.0, DATE, TravelMode.WALKING);

        assertThat(response.assumptions().travelMode()).isEqualTo(TravelMode.WALKING);
        assertThat(response.assumptions().candidateRadiusMeters()).isEqualTo(3_000);
        assertThat(response.assumptions().costPerKm()).isEqualByComparingTo("0");
        assertThat(response.recommendedBalance().travelCost()).isEqualByComparingTo("0");
        // Dva kilometra (tamo i nazad) brzinom 4,5 km/h.
        assertThat(response.recommendedBalance().routeDurationSeconds()).isEqualTo(1_600L);
    }

    /**
     * Šesti test 6.11.: on foot the plan walked 50 minutes to save about
     * 600 RSD. The hour of walking counts in the choice at the shopper's
     * price; the response shows the basket and the minutes, no money for time.
     */
    @Test
    void onFootTheHourOfWalkingCountsInTheChoice() {
        // Far shop: 3,8 km there and back, about 51 min, 338 RSD at 400 RSD/h.
        ShoppingRecommendationResponse smallSaving = walkFarForLess(200, TestMarkets.serbia());
        assertThat(smallSaving.recommendedBalance().stores())
                .extracting(store -> store.storeId()).containsExactly(1L);
        assertThat(smallSaving.lowestPrice().stores())
                .extracting(store -> store.storeId()).containsExactly(2L);
        assertThat(smallSaving.recommendedBalance().timeCost()).isEqualByComparingTo("0");
        assertThat(smallSaving.recommendedBalance().totalCost())
                .isEqualByComparingTo(smallSaving.recommendedBalance().basketCost());
        assertThat(smallSaving.recommendedBalance().explanation())
                .contains("po tvojoj ceni vremena")
                .doesNotContain("ne računamo put ni vreme");

        ShoppingRecommendationResponse bigSaving = walkFarForLess(600, TestMarkets.serbia());
        assertThat(bigSaving.recommendedBalance().stores())
                .extracting(store -> store.storeId()).containsExactly(2L);

        // The same 600 RSD is not worth the walk to whom an hour is worth 1.000 RSD.
        rs.pametnakupovina.backend.market.Market dearHour = TestMarkets.serbia();
        dearHour = new rs.pametnakupovina.backend.market.Market(
                dearHour.id(), dearHour.code(), dearHour.name(), dearHour.currencyCode(),
                dearHour.currencyMinorUnits(), dearHour.locale(), dearHour.defaultLanguage(),
                dearHour.timeZone(), dearHour.travelCostPerKm(), new BigDecimal("1000.00"),
                dearHour.costPerStop());
        ShoppingRecommendationResponse dear = walkFarForLess(600, dearHour);
        assertThat(dear.recommendedBalance().stores())
                .extracting(store -> store.storeId()).containsExactly(1L);
        assertThat(dear.assumptions().valuePerHour()).isEqualByComparingTo("1000");
        assertThat(dear.assumptions().costPerKm()).isEqualByComparingTo("0");
    }

    /**
     * For every saving and way of going the rules hold: Najjeftinija korpa
     * is the lowest basket, and by car Najbolje ukupno the lowest total
     * (on foot the total shown is the basket, so only the choice counts time).
     */
    @Test
    void byCarAndOnFootTheBestTotalIsNeverDearerThanAPlanOfTheSameItems() {
        for (int saving : new int[] {50, 200, 600, 1500}) {
            for (TravelMode mode : TravelMode.values()) {
                ShoppingRecommendationResponse response =
                        walkFarForLess(saving, TestMarkets.serbia(), mode);
                OptimizationScenarioResponse best = response.recommendedBalance();
                assertThat(response.lowestPrice().coveredItems()).isEqualTo(best.coveredItems());
                assertThat(response.lowestPrice().basketCost())
                        .isLessThanOrEqualTo(best.basketCost());
                if (mode == TravelMode.DRIVING) {
                    assertThat(best.totalCost()).isLessThanOrEqualTo(response.singleStore().totalCost());
                    assertThat(best.totalCost()).isLessThanOrEqualTo(response.lowestPrice().totalCost());
                }
            }
        }
    }

    private ShoppingRecommendationResponse walkFarForLess(int saving, rs.pametnakupovina.backend.market.Market market) {
        return walkFarForLess(saving, market, TravelMode.WALKING);
    }

    private ShoppingRecommendationResponse walkFarForLess(
            int saving,
            rs.pametnakupovina.backend.market.Market market,
            TravelMode mode
    ) {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        MarketRepository markets = mock(MarketRepository.class);
        when(markets.forPlanningShoppingList(anyLong())).thenReturn(market);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets);

        ShoppingListItemResponse basket = item(1L, "Bakin spisak", 101L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(basket))));
        NearbyStore near = store(1L, "A");
        NearbyStore far = store(2L, "B");
        when(nearbyRepository.findPricingEligibleNearby(eq(SERBIA), eq(44.0), eq(19.0),
                org.mockito.ArgumentMatchers.anyInt(), eq(20)))
                .thenReturn(List.of(near, far));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                        offer(near, basket, 2000 + saving, 1001L),
                        offer(far, basket, 2000, 2001L))));
        long[][] meters = {{0, 300, 1_900}, {300, 0, 1_800}, {1_900, 1_800, 0}};
        String[] ids = {"USER", "STORE:1", "STORE:2"};
        List<RouteMatrixEntry> entries = new ArrayList<>();
        for (int from = 0; from < ids.length; from++) {
            for (int to = 0; to < ids.length; to++) {
                entries.add(new RouteMatrixEntry(ids[from], ids[to], meters[from][to], null));
            }
        }
        when(routeProvider.calculate(anyList()))
                .thenReturn(new RouteMatrix("TEST", "STRAIGHT_LINE_ESTIMATE", true, entries));

        return service.recommend(10L, 44.0, 19.0, DATE, mode);
    }

    /** Test 20.10.: +238 RSD and half an hour more only for wet wipes at 25 RSD. */
    @Test
    void anItemWorthLessThanAStopIsBoughtOnTheWayInsteadOfInASecondShop() {
        ShoppingRecommendationResponse response = wipesPlan(25, TravelMode.DRIVING);

        OptimizationScenarioResponse best = response.recommendedBalance();
        assertThat(best.stopCount()).isEqualTo(1);
        assertThat(best.priceSources()).containsExactly("A");
        assertThat(best.unavailableItems()).isZero();
        assertThat(best.onTheWay()).isNotNull();
        assertThat(best.onTheWay().itemNames()).containsExactly("vlažne maramice");
        assertThat(best.onTheWay().itemsCost()).isEqualByComparingTo("25.00");
        assertThat(best.onTheWay().extraCost()).isGreaterThan(new BigDecimal("80"));
        assertThat(best.explanation()).contains("vlažne maramice kupi usput").doesNotContain("Nije sve");
        assertThat(best.items())
                .filteredOn(line -> line.itemId() == 3L)
                .singleElement()
                .satisfies(line -> assertThat(line.explanation()).startsWith("Kupi usput."));
        // Whoever wants everything in one go still sees it.
        assertThat(response.lowestPrice().stopCount()).isEqualTo(2);
        assertThat(response.lowestPrice().onTheWay()).isNull();
    }

    @Test
    void anItemWorthTheStopStillGetsItsShop() {
        OptimizationScenarioResponse best = wipesPlan(90, TravelMode.DRIVING).recommendedBalance();

        assertThat(best.stopCount()).isEqualTo(2);
        assertThat(best.coveredItems()).isEqualTo(3);
        assertThat(best.onTheWay()).isNull();
    }

    private ShoppingRecommendationResponse wipesPlan(int wipesPrice, TravelMode travelMode) {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets());

        ShoppingListItemResponse milk = item(1L, "Mleko", 101L);
        ShoppingListItemResponse bread = item(2L, "Hleb", 102L);
        ShoppingListItemResponse wipes = item(3L, "vlažne maramice", 103L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(milk, bread, wipes))));

        NearbyStore idea = store(1L, "A");
        NearbyStore maxi = store(2L, "B");
        when(nearbyRepository.findPricingEligibleNearby(eq(SERBIA), eq(44.0), eq(19.0), org.mockito.ArgumentMatchers.anyInt(), eq(20)))
                .thenReturn(List.of(idea, maxi));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                        offer(idea, milk, 120, 1001L),
                        offer(idea, bread, 70, 1002L),
                        offer(maxi, wipes, wipesPrice, 2003L)
                )));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix());

        return service.recommend(10L, 44.0, 19.0, DATE, travelMode);
    }

    /** "Samo radnje do 1 km": only that circle, and nothing farther is looked for. */
    @Test
    void aTighterCircleKeepsThePlanToShopsCloseBy() {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets());

        ShoppingListItemResponse milk = item(1L, "Mleko", 101L);
        ShoppingListItemResponse rare = item(2L, "Retkost", 102L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(milk, rare))));
        NearbyStore near = store(1L, "A");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 1_000, 20))
                .thenReturn(List.of(near));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(offer(near, milk, 100, 1001L))));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix(List.of(1L)));

        ShoppingRecommendationResponse response =
                service.recommend(10L, 44.0, 19.0, DATE, TravelMode.WALKING, false, 1_000);

        assertThat(response.assumptions().candidateRadiusMeters()).isEqualTo(1_000);
        assertThat(response.recommendedBalance().stopCount()).isEqualTo(1);
        org.mockito.Mockito.verify(nearbyRepository, org.mockito.Mockito.never())
                .findNearestCarrying(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(), anyList(), anyList(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        // On foot the usual circle is 3 km; a wider one is not "close by".
        assertThatThrownBy(() -> service.recommend(10L, 44.0, 19.0, DATE, TravelMode.WALKING, false, 5_000))
                .isInstanceOf(ResponseStatusException.class);
    }

    private ShoppingListItemResponse item(
            Long itemId,
            String name,
            Long canonicalProductId
    ) {
        return new ShoppingListItemResponse(
                itemId,
                name,
                name,
                null,
                BigDecimal.ONE,
                ShoppingItemRule.EXACT_PRODUCT,
                ShoppingItemMatchingStatus.CONFIRMED,
                canonicalProductId,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    private NearbyStore store(Long id, String retailerCode) {
        return new NearbyStore(
                id,
                retailerCode,
                "Retailer " + retailerCode,
                "STANDARD",
                "Standard",
                "S-" + id,
                "Store " + id,
                "Adresa " + id,
                "Valjevo",
                44.0 + id / 100.0,
                19.0,
                id * 1000.0
        );
    }

    /** Proba 19.10.: "kečap" became METRO's 35 g at 45 RSD, beside 500 g at 99,99 (V139). */
    @Test
    void theUsualSizeElsewhereBeatsACheaperOddOne() {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository,
                nearbyRepository,
                offerRepository,
                routeProvider,
                new ShoppingOptimizationProperties(),
                markets()
        );

        ShoppingListItemResponse ketchup = item(1L, "kečap", 101L);
        ShoppingListItemResponse bread = item(2L, "Hleb", 102L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(ketchup, bread))));

        NearbyStore metro = store(1L, "A");
        NearbyStore idea = store(2L, "B");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 15_000, 20))
                .thenReturn(List.of(metro, idea));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                        odd(offer(metro, ketchup, 45, 1001L)),
                        offer(metro, bread, 60, 1002L),
                        offer(idea, ketchup, 100, 2001L),
                        offer(idea, bread, 70, 2002L)
                )));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix());

        ShoppingRecommendationResponse response = service.recommend(10L, 44.0, 19.0, DATE);

        assertThat(response.lowestPrice().basketCost()).isEqualByComparingTo("160.00");
        assertThat(response.lowestPrice().priceSources()).containsExactlyInAnyOrder("A", "B");
    }

    /**
     * Provera uživo 23.10.: a plan with Lidl and IDEA took Lidl's young cheese
     * for "krem sir" though IDEA had one, and cow's milk for "sojino mleko"
     * though another shop had soy.
     */
    @Test
    void whatWasAskedElsewhereBeatsACheaperSubstitute() {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository,
                nearbyRepository,
                offerRepository,
                routeProvider,
                new ShoppingOptimizationProperties(),
                markets()
        );

        ShoppingListItemResponse creamCheese = item(1L, "krem sir", 101L);
        ShoppingListItemResponse soyMilk = item(2L, "sojino mleko", 102L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(creamCheese, soyMilk))));

        NearbyStore lidl = store(1L, "A");
        NearbyStore idea = store(2L, "B");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 15_000, 20))
                .thenReturn(List.of(lidl, idea));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                        substitute(offer(lidl, creamCheese, 150, 1001L)),
                        substitute(offer(lidl, soyMilk, 100, 1002L)),
                        offer(idea, creamCheese, 250, 2001L),
                        offer(idea, soyMilk, 200, 2002L)
                )));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix());

        ShoppingRecommendationResponse response = service.recommend(10L, 44.0, 19.0, DATE);

        assertThat(response.lowestPrice().basketCost()).isEqualByComparingTo("450.00");
        assertThat(response.lowestPrice().priceSources()).containsExactly("B");
        assertThat(response.recommendedBalance().basketCost()).isEqualByComparingTo("450.00");
    }

    /**
     * Proba 5.11.2026: Najjeftinija korpa was 4.069,87 RSD and Jedna stanica
     * 3.829,87 RSD for the same seven items. The two-shop plan had one more
     * product of the usual kind, and that alone kept the dearer one.
     */
    @Test
    void theCheapestBasketIsNeverDearerThanOneShopWithTheSameItems() {
        ShoppingListRepository listRepository = mock(ShoppingListRepository.class);
        NearbyStoreRepository nearbyRepository = mock(NearbyStoreRepository.class);
        StoreShoppingOfferRepository offerRepository = mock(StoreShoppingOfferRepository.class);
        RouteMatrixProvider routeProvider = mock(RouteMatrixProvider.class);
        ShoppingRecommendationService service = new ShoppingRecommendationService(
                listRepository, nearbyRepository, offerRepository, routeProvider,
                new ShoppingOptimizationProperties(), markets());

        ShoppingListItemResponse powder = item(1L, "Persil 4,5 kg", 101L);
        ShoppingListItemResponse eggs = item(2L, "6 jaja L", 102L);
        when(listRepository.findById(10L)).thenReturn(Optional.of(new ShoppingListResponse(
                10L, "Test korpa", OffsetDateTime.now(), OffsetDateTime.now(), List.of(powder, eggs))));

        NearbyStore idea = store(1L, "A");
        NearbyStore maxi = store(2L, "B");
        when(nearbyRepository.findPricingEligibleNearby(SERBIA, 44.0, 19.0, 15_000, 20))
                .thenReturn(List.of(idea, maxi));
        when(offerRepository.findOffers(eq(10L), anyList(), eq(DATE), eq(true)))
                .thenAnswer(call -> offersFor(call.getArgument(1), List.of(
                        odd(offer(idea, powder, 1499, 1001L)),
                        offer(idea, eggs, 200, 1002L),
                        offer(maxi, powder, 2040, 2001L),
                        odd(offer(maxi, eggs, 150, 2002L))
                )));
        when(routeProvider.calculate(anyList())).thenReturn(routeMatrix());

        ShoppingRecommendationResponse response = service.recommend(10L, 44.0, 19.0, DATE);

        assertThat(response.singleStore().basketCost()).isEqualByComparingTo("1699.00");
        assertThat(response.lowestPrice().basketCost())
                .isLessThanOrEqualTo(response.singleStore().basketCost())
                .isEqualByComparingTo("1699.00");
        assertThat(response.lowestPrice().priceSources()).containsExactly("A");
    }

    private static StoreItemOffer substitute(StoreItemOffer o) {
        return new StoreItemOffer(o.storeId(), o.retailerCode(), o.retailerName(), o.storeFormatCode(),
                o.storeFormatName(), o.storeName(), o.address(), o.city(), o.latitude(), o.longitude(),
                o.itemId(), o.requestedName(), o.requestedQuantity(), o.matchingRule(), o.matchingStatus(),
                o.retailerProductId(), o.canonicalProductId(), o.productName(), o.productBrand(),
                o.productBarcode(), o.priceDate(), o.regularPrice(), o.discountedPrice(), o.effectivePrice(),
                o.lineTotal(), o.priceScope(), o.purchaseQuantity(), true, true);
    }

    private static StoreItemOffer odd(StoreItemOffer o) {
        return new StoreItemOffer(o.storeId(), o.retailerCode(), o.retailerName(), o.storeFormatCode(),
                o.storeFormatName(), o.storeName(), o.address(), o.city(), o.latitude(), o.longitude(),
                o.itemId(), o.requestedName(), o.requestedQuantity(), o.matchingRule(), o.matchingStatus(),
                o.retailerProductId(), o.canonicalProductId(), o.productName(), o.productBrand(),
                o.productBarcode(), o.priceDate(), o.regularPrice(), o.discountedPrice(), o.effectivePrice(),
                o.lineTotal(), o.priceScope(), o.purchaseQuantity(), false);
    }

    private StoreItemOffer offer(
            NearbyStore store,
            ShoppingListItemResponse item,
            int price,
            Long retailerProductId
    ) {
        BigDecimal value = BigDecimal.valueOf(price)
                .setScale(2);

        return new StoreItemOffer(
                store.storeId(),
                store.retailerCode(),
                store.retailerName(),
                store.storeFormatCode(),
                store.storeFormatName(),
                store.storeName(),
                store.address(),
                store.city(),
                store.latitude(),
                store.longitude(),
                item.id(),
                item.name(),
                item.quantity(),
                item.matchingRule(),
                item.matchingStatus(),
                retailerProductId,
                item.matchedCanonicalProductId(),
                item.name(),
                "Test brend",
                null,
                DATE,
                value,
                null,
                value,
                value,
                "RETAILER"
        );
    }

    private StoreItemOffer datedOffer(
            Long storeId,
            Long itemId,
            int price,
            LocalDate priceDate
    ) {
        BigDecimal value = BigDecimal.valueOf(price).setScale(2);

        return new StoreItemOffer(
                storeId,
                "TEST",
                "Test retailer",
                "STANDARD",
                "Standard",
                "Store " + storeId,
                "Adresa",
                "Valjevo",
                44.0,
                19.0,
                itemId,
                "voda",
                BigDecimal.ONE,
                ShoppingItemRule.FLEXIBLE_CATEGORY,
                ShoppingItemMatchingStatus.UNMATCHED,
                storeId * 100,
                null,
                "Voda mineralna 1 l",
                null,
                null,
                priceDate,
                value,
                null,
                value,
                value,
                "RETAILER"
        );
    }

    private RouteMatrix routeMatrix() {
        return routeMatrix(List.of(1L, 2L));
    }

    private RouteMatrix routeMatrix(List<Long> storeIds) {
        List<String> ids = new ArrayList<>(List.of("USER"));
        storeIds.forEach(id -> ids.add("STORE:" + id));
        List<RouteMatrixEntry> entries = new ArrayList<>();

        for (String origin : ids) {
            for (String destination : ids) {
                long distance;

                if (origin.equals(destination)) {
                    distance = 0;
                } else if (origin.equals("USER")
                        || destination.equals("USER")) {
                    distance = 1_000;
                } else {
                    distance = 2_000;
                }

                entries.add(
                        new RouteMatrixEntry(
                                origin,
                                destination,
                                distance,
                                null
                        )
                );
            }
        }

        return new RouteMatrix(
                "TEST",
                "STRAIGHT_LINE_ESTIMATE",
                true,
                entries
        );
    }

    /** Answers whichever group of shops the service asks for, as the query would. */
    private static List<StoreItemOffer> offersFor(
            List<Long> storeIds,
            List<StoreItemOffer> offers
    ) {
        return offers.stream()
                .filter(offer -> storeIds.contains(offer.storeId()))
                .toList();
    }

    private static MarketRepository markets() {
        MarketRepository markets = mock(MarketRepository.class);
        when(markets.forPlanningShoppingList(anyLong())).thenReturn(TestMarkets.serbia());
        return markets;
    }
}
