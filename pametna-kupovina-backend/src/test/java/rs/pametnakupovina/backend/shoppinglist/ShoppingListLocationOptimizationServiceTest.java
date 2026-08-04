package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationRepository;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationResponse;
import rs.pametnakupovina.backend.routing.RouteMatrix;
import rs.pametnakupovina.backend.routing.RouteMatrixEntry;
import rs.pametnakupovina.backend.routing.RouteMatrixProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShoppingListLocationOptimizationServiceTest {

    @Test
    void optimizerUsesProviderContractForBothRouteDirections() {
        ShoppingListOptimizationService priceService =
                mock(ShoppingListOptimizationService.class);

        RetailerLocationRepository locationRepository =
                mock(RetailerLocationRepository.class);

        RouteMatrixProvider routeMatrixProvider = waypoints -> {
            assertThat(waypoints)
                    .extracting("id")
                    .containsExactly("USER", "STORE:10");

            return new RouteMatrix(
                    "TEST_PROVIDER",
                    "TEST_MATRIX",
                    false,
                    List.of(
                            new RouteMatrixEntry(
                                    "USER",
                                    "USER",
                                    0,
                                    0L
                            ),
                            new RouteMatrixEntry(
                                    "USER",
                                    "STORE:10",
                                    1_500,
                                    120L
                            ),
                            new RouteMatrixEntry(
                                    "STORE:10",
                                    "USER",
                                    2_500,
                                    180L
                            ),
                            new RouteMatrixEntry(
                                    "STORE:10",
                                    "STORE:10",
                                    0,
                                    0L
                            )
                    )
            );
        };

        RetailerBasketOption storeOption =
                new RetailerBasketOption(
                        "TEST",
                        "Test retailer",
                        1,
                        0,
                        true,
                        new BigDecimal("100.00"),
                        List.of()
                );

        ShoppingListPriceItem priceItem =
                new ShoppingListPriceItem(
                        1L,
                        "Mleko",
                        "8600000000008",
                        BigDecimal.ONE,
                        true,
                        1L,
                        "Mleko 1 l",
                        "TEST",
                        "Test retailer",
                        LocalDate.of(2026, 8, 4),
                        new BigDecimal("100.00"),
                        null,
                        new BigDecimal("100.00"),
                        new BigDecimal("100.00")
                );

        when(priceService.optimize(1L)).thenReturn(
                new ShoppingListOptimizationResponse(
                        1L,
                        "Test list",
                        1,
                        1,
                        0,
                        1,
                        new BigDecimal("100.00"),
                        storeOption,
                        BigDecimal.ZERO,
                        "SINGLE_STORE",
                        List.of(priceItem),
                        List.of(storeOption)
                )
        );

        when(locationRepository.findNearestForEachRetailer(44, 19))
                .thenReturn(
                        List.of(
                                new RetailerLocationResponse(
                                        10L,
                                        "TEST",
                                        "Test retailer",
                                        "Test store",
                                        "Test address",
                                        "Valjevo",
                                        44.01,
                                        19.01,
                                        1.5
                                )
                        )
                );

        ShoppingListLocationOptimizationService service =
                new ShoppingListLocationOptimizationService(
                        priceService,
                        locationRepository,
                        routeMatrixProvider
                );

        LocationOptimizationResponse result = service.optimize(
                1L,
                44,
                19,
                new BigDecimal("10.00")
        );

        assertThat(result.distanceMethod())
                .isEqualTo("TEST_MATRIX");
        assertThat(result.recommendedStrategy().strategy())
                .isEqualTo("SINGLE_STORE");
        assertThat(result.recommendedStrategy().routeDistanceKm())
                .isEqualTo(4.0);
        assertThat(result.recommendedStrategy().travelCost())
                .isEqualByComparingTo("40.00");
        assertThat(result.recommendedStrategy().finalTotal())
                .isEqualByComparingTo("140.00");
    }
}
