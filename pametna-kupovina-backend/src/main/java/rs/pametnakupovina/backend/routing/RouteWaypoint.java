package rs.pametnakupovina.backend.routing;

public record RouteWaypoint(
        String id,
        double latitude,
        double longitude
) {
    public RouteWaypoint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Waypoint id je obavezan"
            );
        }

        id = id.trim();

        if (!Double.isFinite(latitude)
                || latitude < -90
                || latitude > 90) {
            throw new IllegalArgumentException(
                    "Waypoint latitude mora biti između -90 i 90"
            );
        }

        if (!Double.isFinite(longitude)
                || longitude < -180
                || longitude > 180) {
            throw new IllegalArgumentException(
                    "Waypoint longitude mora biti između -180 i 180"
            );
        }
    }
}
