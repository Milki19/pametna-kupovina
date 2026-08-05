package rs.pametnakupovina.backend.routing;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record RouteMatrix(
        String providerCode,
        String distanceMethod,
        boolean approximate,
        List<RouteMatrixEntry> entries
) {
    public RouteMatrix {
        if (providerCode == null || providerCode.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider code je obavezan"
            );
        }

        if (distanceMethod == null || distanceMethod.isBlank()) {
            throw new IllegalArgumentException(
                    "Distance method je obavezan"
            );
        }

        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "Route matrix mora imati bar jedan element"
            );
        }

        providerCode = providerCode.trim();
        distanceMethod = distanceMethod.trim();
        entries = List.copyOf(entries);

        Set<RouteMatrixKey> keys = new HashSet<>();

        for (RouteMatrixEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Route matrix element ne može biti null"
                );
            }

            RouteMatrixKey key = new RouteMatrixKey(
                    entry.originId(),
                    entry.destinationId()
            );

            if (!keys.add(key)) {
                throw new IllegalArgumentException(
                        "Dupliran route matrix element: "
                                + entry.originId()
                                + " -> "
                                + entry.destinationId()
                );
            }
        }
    }

    public RouteMatrixEntry entry(
            String originId,
            String destinationId
    ) {
        for (RouteMatrixEntry entry : entries) {
            if (entry.originId().equals(originId)
                    && entry.destinationId().equals(destinationId)) {
                return entry;
            }
        }

        throw new IllegalArgumentException(
                "Nedostaje route matrix element: "
                        + originId
                        + " -> "
                        + destinationId
        );
    }

    public double distanceKilometers(
            String originId,
            String destinationId
    ) {
        return entry(originId, destinationId)
                .distanceMeters() / 1000.0;
    }

    public Long durationSeconds(
            String originId,
            String destinationId
    ) {
        return entry(originId, destinationId).durationSeconds();
    }

    private record RouteMatrixKey(
            String originId,
            String destinationId
    ) {
    }
}
