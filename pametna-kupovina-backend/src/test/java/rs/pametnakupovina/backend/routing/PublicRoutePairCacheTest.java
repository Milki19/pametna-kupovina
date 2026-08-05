package rs.pametnakupovina.backend.routing;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRoutePairCacheTest {

    @Test
    void cachesPublicStorePairsButNeverPreciseUserLocation() {
        PublicRoutePairCache cache = new PublicRoutePairCache(
                Clock.fixed(
                        Instant.parse("2026-08-05T10:00:00Z"),
                        ZoneOffset.UTC
                )
        );

        RouteWaypoint user = new RouteWaypoint(
                "USER",
                44.123456,
                19.654321
        );

        RouteWaypoint firstStore = new RouteWaypoint(
                "STORE:1",
                44.20,
                19.80
        );

        RouteWaypoint secondStore = new RouteWaypoint(
                "STORE:2",
                44.30,
                19.90
        );

        RouteMatrixEntry storePair = new RouteMatrixEntry(
                firstStore.id(),
                secondStore.id(),
                2_000,
                240L
        );

        cache.put(
                "OSRM",
                firstStore,
                secondStore,
                storePair,
                60
        );

        cache.put(
                "OSRM",
                user,
                firstStore,
                new RouteMatrixEntry(
                        user.id(),
                        firstStore.id(),
                        1_000,
                        120L
                ),
                60
        );

        assertThat(cache.get(
                "OSRM",
                firstStore,
                secondStore
        )).contains(storePair);

        assertThat(cache.get("OSRM", user, firstStore)).isEmpty();
        assertThat(cache.size()).isEqualTo(1);
    }
}
