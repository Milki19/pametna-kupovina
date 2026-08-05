package rs.pametnakupovina.backend.privacy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationController;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationService;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListLocationOptimizationController;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListLocationOptimizationService;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListService;
import rs.pametnakupovina.backend.store.NearbyStoreController;
import rs.pametnakupovina.backend.store.NearbyStoreService;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PreciseLocationControllerBoundaryTest {

    private static final double LATITUDE = 44.234567;
    private static final double LONGITUDE = 19.876543;

    @Test
    void everyUserLocationControllerUsesTheRequestOnlyBoundary() {
        PreciseLocationPolicy policy = new PreciseLocationPolicy();

        ShoppingListLocationOptimizationService optimizationService =
                mock(ShoppingListLocationOptimizationService.class);

        ShoppingListService shoppingListService =
                mock(ShoppingListService.class);

        NearbyStoreService nearbyStoreService =
                mock(NearbyStoreService.class);

        RetailerLocationService retailerLocationService =
                mock(RetailerLocationService.class);

        ListAppender<ILoggingEvent> appender = startAppender();

        try {
            new ShoppingListLocationOptimizationController(
                    optimizationService,
                    shoppingListService,
                    policy
            ).optimize(
                    1L,
                    "test-client-token",
                    LATITUDE,
                    LONGITUDE,
                    new BigDecimal("20.00")
            );

            new NearbyStoreController(
                    nearbyStoreService,
                    policy
            ).findNearby(
                    LATITUDE,
                    LONGITUDE,
                    5_000,
                    20
            );

            new RetailerLocationController(
                    retailerLocationService,
                    policy
            ).findNearest(
                    LATITUDE,
                    LONGITUDE,
                    10
            );

            verify(optimizationService).optimize(
                    1L,
                    LATITUDE,
                    LONGITUDE,
                    new BigDecimal("20.00")
            );

            verify(shoppingListService).requireOwnedList(
                    1L,
                    "test-client-token"
            );

            verify(nearbyStoreService).findNearby(
                    LATITUDE,
                    LONGITUDE,
                    5_000,
                    20
            );

            verify(retailerLocationService).findNearest(
                    LATITUDE,
                    LONGITUDE,
                    10
            );

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .hasSize(3)
                    .anyMatch(message -> message.contains(
                            "SHOPPING_LIST_OPTIMIZATION"
                    ))
                    .anyMatch(message -> message.contains(
                            "NEARBY_STORES"
                    ))
                    .anyMatch(message -> message.contains(
                            "NEAREST_RETAILER_LOCATIONS"
                    ))
                    .allMatch(message -> message.contains(
                            "retention=REQUEST_ONLY"
                    ))
                    .allMatch(message -> !message.contains(
                            Double.toString(LATITUDE)
                    ))
                    .allMatch(message -> !message.contains(
                            Double.toString(LONGITUDE)
                    ));
        } finally {
            stopAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> startAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger().addAppender(appender);
        return appender;
    }

    private void stopAppender(ListAppender<ILoggingEvent> appender) {
        logger().detachAppender(appender);
        appender.stop();
    }

    private Logger logger() {
        return (Logger) LoggerFactory.getLogger(
                PreciseLocationPolicy.class
        );
    }
}
