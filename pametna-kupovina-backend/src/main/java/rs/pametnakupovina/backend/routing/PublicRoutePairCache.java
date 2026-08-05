package rs.pametnakupovina.backend.routing;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class PublicRoutePairCache {

    private final ConcurrentMap<RoutePairKey, CachedRoutePair> entries =
            new ConcurrentHashMap<>();

    private final Clock clock;

    public PublicRoutePairCache() {
        this(Clock.systemUTC());
    }

    PublicRoutePairCache(Clock clock) {
        this.clock = clock;
    }

    public Optional<RouteMatrixEntry> get(
            String providerCode,
            RouteWaypoint origin,
            RouteWaypoint destination
    ) {
        if (!isPublicStorePair(origin, destination)) {
            return Optional.empty();
        }

        RoutePairKey key = key(providerCode, origin, destination);
        CachedRoutePair cached = entries.get(key);

        if (cached == null) {
            return Optional.empty();
        }

        if (!cached.expiresAt().isAfter(clock.instant())) {
            entries.remove(key, cached);
            return Optional.empty();
        }

        return Optional.of(cached.entry());
    }

    public void put(
            String providerCode,
            RouteWaypoint origin,
            RouteWaypoint destination,
            RouteMatrixEntry entry,
            long ttlSeconds
    ) {
        if (!isPublicStorePair(origin, destination)
                || ttlSeconds <= 0) {
            return;
        }

        entries.put(
                key(providerCode, origin, destination),
                new CachedRoutePair(
                        entry,
                        clock.instant().plusSeconds(ttlSeconds)
                )
        );
    }

    int size() {
        return entries.size();
    }

    private boolean isPublicStorePair(
            RouteWaypoint origin,
            RouteWaypoint destination
    ) {
        return origin.id().startsWith("STORE:")
                && destination.id().startsWith("STORE:");
    }

    private RoutePairKey key(
            String providerCode,
            RouteWaypoint origin,
            RouteWaypoint destination
    ) {
        return new RoutePairKey(
                providerCode,
                origin.id(),
                origin.latitude(),
                origin.longitude(),
                destination.id(),
                destination.latitude(),
                destination.longitude()
        );
    }

    private record RoutePairKey(
            String providerCode,
            String originId,
            double originLatitude,
            double originLongitude,
            String destinationId,
            double destinationLatitude,
            double destinationLongitude
    ) {
    }

    private record CachedRoutePair(
            RouteMatrixEntry entry,
            Instant expiresAt
    ) {
    }
}
