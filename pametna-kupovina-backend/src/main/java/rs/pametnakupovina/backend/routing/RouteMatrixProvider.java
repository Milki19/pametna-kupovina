package rs.pametnakupovina.backend.routing;

import java.util.List;

public interface RouteMatrixProvider {

    RouteMatrix calculate(List<RouteWaypoint> waypoints);
}
