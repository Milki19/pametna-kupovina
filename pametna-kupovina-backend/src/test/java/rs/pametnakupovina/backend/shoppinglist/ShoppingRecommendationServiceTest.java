package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingRecommendationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 5);

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
                        properties
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

        when(nearbyRepository.findNearby(
                44.0,
                19.0,
                15_000,
                20
        )).thenReturn(List.of(firstStore, secondStore));

        when(offerRepository.findOffers(
                10L,
                List.of(1L, 2L),
                DATE
        )).thenReturn(List.of(
                offer(firstStore, firstItem, 100, 1001L),
                offer(firstStore, secondItem, 300, 1002L),
                offer(secondStore, firstItem, 130, 2001L),
                offer(secondStore, secondItem, 100, 2002L)
        ));

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
                        new ShoppingOptimizationProperties()
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

        when(nearbyRepository.findNearby(
                44.0,
                19.0,
                15_000,
                20
        )).thenReturn(List.of(store));

        when(offerRepository.findOffers(
                12L,
                List.of(1L),
                DATE
        )).thenReturn(List.of());

        when(routeProvider.calculate(anyList()))
                .thenReturn(routeMatrix());

        ShoppingRecommendationResponse response =
                new ShoppingRecommendationService(
                        listRepository,
                        nearbyRepository,
                        offerRepository,
                        routeProvider,
                        new ShoppingOptimizationProperties()
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

    private RouteMatrix routeMatrix() {
        List<String> ids = List.of("USER", "STORE:1", "STORE:2");
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
}
