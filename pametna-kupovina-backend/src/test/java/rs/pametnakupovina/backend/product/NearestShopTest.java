package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A product's nearest shop is the nearest shop of a chain that sells it. */
class NearestShopTest {

    @Test
    void theNearestChainThatSellsItInWholeMetres() {
        Map<Long, Double> meters = CanonicalProductSearchRepository.nearestStoreMeters(
                Map.of(
                        1L, List.of(10L, 20L),
                        2L, List.of(30L),
                        3L, List.of(20L)
                ),
                Map.of(10L, 1234.56, 20L, 177.4)
        );

        assertThat(meters).containsExactlyInAnyOrderEntriesOf(Map.of(1L, 177.0, 3L, 177.0));
    }
}
