package rs.pametnakupovina.backend.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StraightLineRouteMatrixProviderTest {

    private final StraightLineRouteMatrixProvider provider =
            new StraightLineRouteMatrixProvider();

    @Test
    void calculatesCompleteSymmetricMatrixWithMetadata() {
        RouteMatrix matrix = provider.calculate(
                List.of(
                        new RouteWaypoint("A", 0, 0),
                        new RouteWaypoint("B", 0, 1)
                )
        );

        assertThat(matrix.providerCode())
                .isEqualTo("STRAIGHT_LINE");
        assertThat(matrix.distanceMethod())
                .isEqualTo("STRAIGHT_LINE_ESTIMATE");
        assertThat(matrix.approximate()).isTrue();
        assertThat(matrix.entries()).hasSize(4);

        assertThat(matrix.entry("A", "A").distanceMeters())
                .isZero();
        assertThat(matrix.entry("A", "B").distanceMeters())
                .isBetween(111_194L, 111_196L);
        assertThat(matrix.entry("B", "A").distanceMeters())
                .isEqualTo(
                        matrix.entry("A", "B").distanceMeters()
                );
        assertThat(matrix.entry("A", "B").durationSeconds())
                .isNull();
    }

    @Test
    void rejectsDuplicateWaypointIds() {
        assertThatThrownBy(
                () -> provider.calculate(
                        List.of(
                                new RouteWaypoint("STORE:1", 44, 19),
                                new RouteWaypoint("STORE:1", 45, 20)
                        )
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jedinstven");
    }

    @Test
    void routeContractRejectsInvalidCoordinatesAndMissingPair() {
        assertThatThrownBy(
                () -> new RouteWaypoint("A", Double.NaN, 19)
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");

        RouteMatrix matrix = provider.calculate(
                List.of(new RouteWaypoint("A", 44, 19))
        );

        assertThatThrownBy(() -> matrix.entry("A", "B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A -> B");
    }
}
