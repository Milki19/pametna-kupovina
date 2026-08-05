package rs.pametnakupovina.backend.routing;

import tools.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@Primary
@ConditionalOnProperty(
        prefix = "routing.osrm",
        name = "enabled",
        havingValue = "true"
)
public class OsrmRouteMatrixProvider implements RouteMatrixProvider {

    private static final String PROVIDER_CODE = "OSRM";

    private final OsrmRoutingProperties properties;
    private final PublicRoutePairCache pairCache;
    private final StraightLineRouteMatrixProvider fallbackProvider;
    private final RestClient restClient;

    public OsrmRouteMatrixProvider(
            OsrmRoutingProperties properties,
            PublicRoutePairCache pairCache,
            StraightLineRouteMatrixProvider fallbackProvider,
            RestClient.Builder restClientBuilder
    ) {
        this(
                properties,
                pairCache,
                fallbackProvider,
                restClientBuilder.build()
        );
    }

    OsrmRouteMatrixProvider(
            OsrmRoutingProperties properties,
            PublicRoutePairCache pairCache,
            StraightLineRouteMatrixProvider fallbackProvider,
            RestClient restClient
    ) {
        this.properties = properties;
        this.pairCache = pairCache;
        this.fallbackProvider = fallbackProvider;
        this.restClient = restClient;
    }

    @Override
    public RouteMatrix calculate(List<RouteWaypoint> waypoints) {
        List<RouteWaypoint> validatedWaypoints =
                validateAndCopy(waypoints);

        RouteMatrix cachedMatrix = matrixFromCache(
                validatedWaypoints
        );

        if (cachedMatrix != null) {
            return cachedMatrix;
        }

        try {
            RouteMatrix matrix = requestMatrix(validatedWaypoints);
            cachePublicPairs(validatedWaypoints, matrix);
            return matrix;
        } catch (RuntimeException exception) {
            return fallbackProvider.calculate(validatedWaypoints);
        }
    }

    private RouteMatrix requestMatrix(
            List<RouteWaypoint> waypoints
    ) {
        URI uri = buildUri(waypoints);

        JsonNode body = restClient.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);

        if (body == null
                || !"Ok".equalsIgnoreCase(
                body.path("code").asText()
        )) {
            throw new IllegalStateException(
                    "OSRM nije vratio uspešnu route matricu"
            );
        }

        JsonNode distances = body.path("distances");
        JsonNode durations = body.path("durations");

        if (!distances.isArray()
                || distances.size() != waypoints.size()
                || !durations.isArray()
                || durations.size() != waypoints.size()) {
            throw new IllegalStateException(
                    "OSRM route matrica ima neočekivanu veličinu"
            );
        }

        List<RouteMatrixEntry> entries = new ArrayList<>(
                waypoints.size() * waypoints.size()
        );

        for (int originIndex = 0;
             originIndex < waypoints.size();
             originIndex++) {
            JsonNode distanceRow = distances.get(originIndex);
            JsonNode durationRow = durations.get(originIndex);

            if (!distanceRow.isArray()
                    || distanceRow.size() != waypoints.size()
                    || !durationRow.isArray()
                    || durationRow.size() != waypoints.size()) {
                throw new IllegalStateException(
                        "OSRM route red ima neočekivanu veličinu"
                );
            }

            for (int destinationIndex = 0;
                 destinationIndex < waypoints.size();
                 destinationIndex++) {
                JsonNode distance = distanceRow.get(destinationIndex);
                JsonNode duration = durationRow.get(destinationIndex);

                if (!distance.isNumber() || !duration.isNumber()) {
                    throw new IllegalStateException(
                            "OSRM nije pronašao put između dve tačke"
                    );
                }

                entries.add(
                        new RouteMatrixEntry(
                                waypoints.get(originIndex).id(),
                                waypoints.get(destinationIndex).id(),
                                Math.round(distance.asDouble()),
                                Math.round(duration.asDouble())
                        )
                );
            }
        }

        return new RouteMatrix(
                PROVIDER_CODE,
                "ROAD_ROUTE_MATRIX",
                false,
                entries
        );
    }

    private URI buildUri(List<RouteWaypoint> waypoints) {
        String coordinates = waypoints.stream()
                .map(waypoint -> String.format(
                        Locale.ROOT,
                        "%.7f,%.7f",
                        waypoint.longitude(),
                        waypoint.latitude()
                ))
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();

        String baseUrl = properties.getBaseUrl();

        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String profile = URLEncoder.encode(
                properties.getProfile(),
                StandardCharsets.UTF_8
        );

        return URI.create(
                baseUrl
                        + "/"
                        + profile
                        + "/"
                        + coordinates
                        + "?annotations=distance,duration"
        );
    }

    private RouteMatrix matrixFromCache(
            List<RouteWaypoint> waypoints
    ) {
        if (waypoints.stream().anyMatch(
                waypoint -> !waypoint.id().startsWith("STORE:")
        )) {
            return null;
        }

        List<RouteMatrixEntry> entries = new ArrayList<>();

        for (RouteWaypoint origin : waypoints) {
            for (RouteWaypoint destination : waypoints) {
                RouteMatrixEntry entry = pairCache.get(
                        PROVIDER_CODE,
                        origin,
                        destination
                ).orElse(null);

                if (entry == null) {
                    return null;
                }

                entries.add(entry);
            }
        }

        return new RouteMatrix(
                "OSRM_CACHE",
                "ROAD_ROUTE_MATRIX_CACHED",
                false,
                entries
        );
    }

    private void cachePublicPairs(
            List<RouteWaypoint> waypoints,
            RouteMatrix matrix
    ) {
        for (RouteWaypoint origin : waypoints) {
            for (RouteWaypoint destination : waypoints) {
                pairCache.put(
                        PROVIDER_CODE,
                        origin,
                        destination,
                        matrix.entry(origin.id(), destination.id()),
                        properties.getPublicPairCacheTtlSeconds()
                );
            }
        }
    }

    private List<RouteWaypoint> validateAndCopy(
            List<RouteWaypoint> waypoints
    ) {
        if (waypoints == null || waypoints.isEmpty()) {
            throw new IllegalArgumentException(
                    "Potreban je bar jedan waypoint"
            );
        }

        List<RouteWaypoint> copy = List.copyOf(waypoints);
        Set<String> ids = new HashSet<>();

        for (RouteWaypoint waypoint : copy) {
            if (waypoint == null || !ids.add(waypoint.id())) {
                throw new IllegalArgumentException(
                        "Waypoint-i moraju biti neprazni i jedinstveni"
                );
            }
        }

        return copy;
    }
}
