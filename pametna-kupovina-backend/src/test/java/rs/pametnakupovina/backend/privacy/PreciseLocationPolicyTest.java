package rs.pametnakupovina.backend.privacy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreciseLocationPolicyTest {

    private static final double LATITUDE = 44.123456;
    private static final double LONGITUDE = 19.654321;

    private final PreciseLocationPolicy policy =
            new PreciseLocationPolicy();

    @Test
    void exposesExactCoordinatesOnlyInsideRequestOperation() {
        String result = policy.useForRequest(
                PreciseLocationPurpose.NEARBY_STORES,
                LATITUDE,
                LONGITUDE,
                location -> location.latitude()
                        + ":"
                        + location.longitude()
        );

        assertThat(result).isEqualTo("44.123456:19.654321");

        assertThat(PreciseLocationPolicy.class.getDeclaredFields())
                .allMatch(field -> Modifier.isStatic(
                        field.getModifiers()
                ));
    }

    @Test
    void logsPurposeAndRetentionWithoutCoordinates() {
        ListAppender<ILoggingEvent> appender = startAppender();

        try {
            policy.useForRequest(
                    PreciseLocationPurpose.SHOPPING_LIST_OPTIMIZATION,
                    LATITUDE,
                    LONGITUDE,
                    location -> "done"
            );

            assertSafeLog(
                    appender,
                    "SHOPPING_LIST_OPTIMIZATION",
                    "outcome=SUCCESS"
            );
        } finally {
            stopAppender(appender);
        }
    }

    @Test
    void rejectedRequestStillDoesNotLogCoordinates() {
        ListAppender<ILoggingEvent> appender = startAppender();

        try {
            assertThatThrownBy(() -> policy.useForRequest(
                    PreciseLocationPurpose.NEAREST_RETAILER_LOCATIONS,
                    LATITUDE,
                    LONGITUDE,
                    location -> {
                        throw new IllegalArgumentException("rejected");
                    }
            )).isInstanceOf(IllegalArgumentException.class);

            assertSafeLog(
                    appender,
                    "NEAREST_RETAILER_LOCATIONS",
                    "outcome=REJECTED"
            );
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

    private void assertSafeLog(
            ListAppender<ILoggingEvent> appender,
            String expectedPurpose,
            String expectedOutcome
    ) {
        assertThat(appender.list).hasSize(1);

        String message = appender.list.getFirst()
                .getFormattedMessage();

        assertThat(message)
                .contains(expectedPurpose)
                .contains("retention=REQUEST_ONLY")
                .contains(expectedOutcome)
                .doesNotContain(Double.toString(LATITUDE))
                .doesNotContain(Double.toString(LONGITUDE));
    }
}
