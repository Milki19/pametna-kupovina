package rs.pametnakupovina.backend.routing;

public record RouteMatrixEntry(
        String originId,
        String destinationId,
        long distanceMeters,
        Long durationSeconds
) {
    public RouteMatrixEntry {
        if (originId == null || originId.isBlank()) {
            throw new IllegalArgumentException(
                    "Origin id je obavezan"
            );
        }

        if (destinationId == null || destinationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Destination id je obavezan"
            );
        }

        originId = originId.trim();
        destinationId = destinationId.trim();

        if (distanceMeters < 0) {
            throw new IllegalArgumentException(
                    "Distance ne može biti negativan"
            );
        }

        if (durationSeconds != null && durationSeconds < 0) {
            throw new IllegalArgumentException(
                    "Duration ne može biti negativan"
            );
        }
    }
}
