package rs.pametnakupovina.backend.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class StraightLineRouteMatrixProvider
        implements RouteMatrixProvider {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    @Override
    public RouteMatrix calculate(List<RouteWaypoint> waypoints) {
        List<RouteWaypoint> validatedWaypoints =
                validateAndCopy(waypoints);

        List<RouteMatrixEntry> entries = new ArrayList<>(
                validatedWaypoints.size()
                        * validatedWaypoints.size()
        );

        for (RouteWaypoint origin : validatedWaypoints) {
            for (RouteWaypoint destination : validatedWaypoints) {
                entries.add(
                        new RouteMatrixEntry(
                                origin.id(),
                                destination.id(),
                                distanceMeters(
                                        origin,
                                        destination
                                ),
                                null
                        )
                );
            }
        }

        return new RouteMatrix(
                "STRAIGHT_LINE",
                "STRAIGHT_LINE_ESTIMATE",
                true,
                entries
        );
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
            if (waypoint == null) {
                throw new IllegalArgumentException(
                        "Waypoint ne može biti null"
                );
            }

            if (!ids.add(waypoint.id())) {
                throw new IllegalArgumentException(
                        "Waypoint id mora biti jedinstven: "
                                + waypoint.id()
                );
            }
        }

        return copy;
    }

    private long distanceMeters(
            RouteWaypoint origin,
            RouteWaypoint destination
    ) {
        if (origin.id().equals(destination.id())) {
            return 0;
        }

        double latitudeDifference = Math.toRadians(
                destination.latitude() - origin.latitude()
        );

        double longitudeDifference = Math.toRadians(
                destination.longitude() - origin.longitude()
        );

        double originLatitude = Math.toRadians(origin.latitude());
        double destinationLatitude = Math.toRadians(
                destination.latitude()
        );

        double value =
                Math.sin(latitudeDifference / 2)
                        * Math.sin(latitudeDifference / 2)
                        + Math.cos(originLatitude)
                        * Math.cos(destinationLatitude)
                        * Math.sin(longitudeDifference / 2)
                        * Math.sin(longitudeDifference / 2);

        double boundedValue = Math.min(1.0, value);

        return Math.round(
                EARTH_RADIUS_METERS
                        * 2
                        * Math.atan2(
                        Math.sqrt(boundedValue),
                        Math.sqrt(1 - boundedValue)
                )
        );
    }
}
