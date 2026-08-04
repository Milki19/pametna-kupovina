package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationRepository;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationResponse;
import rs.pametnakupovina.backend.routing.RouteMatrix;
import rs.pametnakupovina.backend.routing.RouteMatrixProvider;
import rs.pametnakupovina.backend.routing.RouteWaypoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ShoppingListLocationOptimizationService {

    private static final String USER_WAYPOINT_ID = "USER";

    private final ShoppingListOptimizationService
            optimizationService;

    private final RetailerLocationRepository
            locationRepository;

    private final RouteMatrixProvider routeMatrixProvider;

    public ShoppingListLocationOptimizationService(
            ShoppingListOptimizationService optimizationService,
            RetailerLocationRepository locationRepository,
            RouteMatrixProvider routeMatrixProvider
    ) {
        this.optimizationService = optimizationService;
        this.locationRepository = locationRepository;
        this.routeMatrixProvider = routeMatrixProvider;
    }

    public LocationOptimizationResponse optimize(
            Long listId,
            double latitude,
            double longitude,
            BigDecimal costPerKm
    ) {
        validate(latitude, longitude, costPerKm);

        ShoppingListOptimizationResponse priceOptimization =
                optimizationService.optimize(listId);

        List<RetailerLocationResponse> nearestLocations =
                locationRepository.findNearestForEachRetailer(
                        latitude,
                        longitude
                );

        Map<String, RetailerLocationResponse> locationsByRetailer =
                new LinkedHashMap<>();

        for (RetailerLocationResponse location : nearestLocations) {
            locationsByRetailer.put(
                    location.retailerCode(),
                    location
            );
        }

        RouteMatrix routeMatrix = createRouteMatrix(
                latitude,
                longitude,
                locationsByRetailer.values()
        );

        PurchaseStrategyResponse multiStoreStrategy =
                createMultiStoreStrategy(
                        priceOptimization,
                        locationsByRetailer,
                        routeMatrix,
                        costPerKm
                );

        List<PurchaseStrategyResponse> singleStoreStrategies =
                new ArrayList<>();

        for (RetailerBasketOption option :
                priceOptimization.singleStoreOptions()) {

            singleStoreStrategies.add(
                    createSingleStoreStrategy(
                            option,
                            locationsByRetailer.get(
                                    option.retailerCode()
                            ),
                            routeMatrix,
                            costPerKm
                    )
            );
        }

        PurchaseStrategyResponse recommendedStrategy = null;

        if (multiStoreStrategy.available()) {
            recommendedStrategy = multiStoreStrategy;
        }

        for (PurchaseStrategyResponse strategy :
                singleStoreStrategies) {

            if (!strategy.available()) {
                continue;
            }

            if (recommendedStrategy == null
                    || strategy.finalTotal().compareTo(
                    recommendedStrategy.finalTotal()
            ) < 0
                    || (
                    strategy.finalTotal().compareTo(
                            recommendedStrategy.finalTotal()
                    ) == 0
                            && strategy.strategy().equals("SINGLE_STORE")
            )) {
                recommendedStrategy = strategy;
            }
        }

        String recommendation =
                recommendedStrategy == null
                        ? "NO_COMPLETE_OPTION"
                        : recommendedStrategy.strategy();

        return new LocationOptimizationResponse(
                priceOptimization.listId(),
                priceOptimization.listName(),
                latitude,
                longitude,
                costPerKm.setScale(
                        2,
                        RoundingMode.HALF_UP
                ),
                "RSD",
                routeMatrix.distanceMethod(),
                recommendation,
                recommendedStrategy,
                multiStoreStrategy,
                singleStoreStrategies
        );
    }

    private PurchaseStrategyResponse createMultiStoreStrategy(
            ShoppingListOptimizationResponse optimization,
            Map<String, RetailerLocationResponse>
                    locationsByRetailer,
            RouteMatrix routeMatrix,
            BigDecimal costPerKm
    ) {
        if (optimization.unmatchedItems() > 0) {
            return unavailableStrategy(
                    "MULTI_STORE",
                    optimization.lowestPriceTotal(),
                    "Nisu povezane sve stavke spiska"
            );
        }

        Set<String> retailerCodes = new LinkedHashSet<>();

        for (ShoppingListPriceItem item :
                optimization.lowestPriceItems()) {

            if (item.matched()) {
                retailerCodes.add(item.retailerCode());
            }
        }

        if (retailerCodes.isEmpty()) {
            return unavailableStrategy(
                    "MULTI_STORE",
                    optimization.lowestPriceTotal(),
                    "Spisak nema dostupne proizvode"
            );
        }

        List<RetailerLocationResponse> locations =
                new ArrayList<>();

        for (String retailerCode : retailerCodes) {
            RetailerLocationResponse location =
                    locationsByRetailer.get(retailerCode);

            if (location == null) {
                return unavailableStrategy(
                        "MULTI_STORE",
                        optimization.lowestPriceTotal(),
                        "Nedostaje lokacija za " + retailerCode
                );
            }

            locations.add(location);
        }

        RouteCalculation route = calculateRoute(
                routeMatrix,
                locations
        );

        BigDecimal travelCost = calculateTravelCost(
                route.totalDistanceKm(),
                costPerKm
        );

        BigDecimal finalTotal =
                optimization.lowestPriceTotal()
                        .add(travelCost)
                        .setScale(
                                2,
                                RoundingMode.HALF_UP
                        );

        List<String> routeRetailerCodes = new ArrayList<>();

        for (RouteStopResponse stop : route.stops()) {
            routeRetailerCodes.add(stop.retailerCode());
        }

        return new PurchaseStrategyResponse(
                "MULTI_STORE",
                true,
                routeRetailerCodes,
                optimization.lowestPriceTotal(),
                route.totalDistanceKm(),
                travelCost,
                finalTotal,
                null,
                route.stops()
        );
    }

    private PurchaseStrategyResponse createSingleStoreStrategy(
            RetailerBasketOption option,
            RetailerLocationResponse location,
            RouteMatrix routeMatrix,
            BigDecimal costPerKm
    ) {
        if (!option.complete()) {
            return unavailableStrategy(
                    "SINGLE_STORE",
                    option.totalPrice(),
                    option.retailerCode()
                            + " nema "
                            + option.missingItems()
                            + " stavki"
            );
        }

        if (location == null) {
            return unavailableStrategy(
                    "SINGLE_STORE",
                    option.totalPrice(),
                    "Nedostaje lokacija za "
                            + option.retailerCode()
            );
        }

        double oneWayDistance = routeMatrix.distanceKilometers(
                USER_WAYPOINT_ID,
                waypointId(location)
        );

        double routeDistance = roundDistance(
                oneWayDistance
                        + routeMatrix.distanceKilometers(
                        waypointId(location),
                        USER_WAYPOINT_ID
                )
        );

        BigDecimal travelCost = calculateTravelCost(
                routeDistance,
                costPerKm
        );

        BigDecimal finalTotal =
                option.totalPrice()
                        .add(travelCost)
                        .setScale(
                                2,
                                RoundingMode.HALF_UP
                        );

        RouteStopResponse stop = new RouteStopResponse(
                1,
                location.retailerCode(),
                location.id(),
                location.locationName(),
                location.city(),
                location.latitude(),
                location.longitude(),
                roundDistance(oneWayDistance)
        );

        return new PurchaseStrategyResponse(
                "SINGLE_STORE",
                true,
                List.of(option.retailerCode()),
                option.totalPrice(),
                routeDistance,
                travelCost,
                finalTotal,
                null,
                List.of(stop)
        );
    }

    private RouteCalculation calculateRoute(
            RouteMatrix routeMatrix,
            List<RetailerLocationResponse> locations
    ) {
        List<RetailerLocationResponse> remaining =
                new ArrayList<>(locations);

        List<RouteStopResponse> stops = new ArrayList<>();

        String currentWaypointId = USER_WAYPOINT_ID;
        double totalDistance = 0;

        while (!remaining.isEmpty()) {
            int nearestIndex = -1;
            double nearestDistance = Double.MAX_VALUE;

            for (int index = 0;
                 index < remaining.size();
                 index++) {

                RetailerLocationResponse candidate =
                        remaining.get(index);

                double distance = routeMatrix.distanceKilometers(
                        currentWaypointId,
                        waypointId(candidate)
                );

                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestIndex = index;
                }
            }

            RetailerLocationResponse nearest =
                    remaining.remove(nearestIndex);

            totalDistance += nearestDistance;

            stops.add(
                    new RouteStopResponse(
                            stops.size() + 1,
                            nearest.retailerCode(),
                            nearest.id(),
                            nearest.locationName(),
                            nearest.city(),
                            nearest.latitude(),
                            nearest.longitude(),
                            roundDistance(nearestDistance)
                    )
            );

            currentWaypointId = waypointId(nearest);
        }

        totalDistance += routeMatrix.distanceKilometers(
                currentWaypointId,
                USER_WAYPOINT_ID
        );

        return new RouteCalculation(
                roundDistance(totalDistance),
                stops
        );
    }

    private BigDecimal calculateTravelCost(
            double distanceKm,
            BigDecimal costPerKm
    ) {
        return costPerKm
                .multiply(BigDecimal.valueOf(distanceKm))
                .setScale(
                        2,
                        RoundingMode.HALF_UP
                );
    }

    private PurchaseStrategyResponse unavailableStrategy(
            String strategy,
            BigDecimal basketTotal,
            String reason
    ) {
        return new PurchaseStrategyResponse(
                strategy,
                false,
                List.of(),
                basketTotal,
                0,
                null,
                null,
                reason,
                List.of()
        );
    }

    private RouteMatrix createRouteMatrix(
            double userLatitude,
            double userLongitude,
            Iterable<RetailerLocationResponse> locations
    ) {
        List<RouteWaypoint> waypoints = new ArrayList<>();

        waypoints.add(
                new RouteWaypoint(
                        USER_WAYPOINT_ID,
                        userLatitude,
                        userLongitude
                )
        );

        Set<Long> storeIds = new LinkedHashSet<>();

        for (RetailerLocationResponse location : locations) {
            if (storeIds.add(location.id())) {
                waypoints.add(
                        new RouteWaypoint(
                                waypointId(location),
                                location.latitude(),
                                location.longitude()
                        )
                );
            }
        }

        return routeMatrixProvider.calculate(waypoints);
    }

    private String waypointId(RetailerLocationResponse location) {
        return "STORE:" + location.id();
    }

    private double roundDistance(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void validate(
            double latitude,
            double longitude,
            BigDecimal costPerKm
    ) {
        if (!Double.isFinite(latitude)
                || latitude < -90
                || latitude > 90) {
            throw badRequest(
                    "Latitude mora biti između -90 i 90"
            );
        }

        if (!Double.isFinite(longitude)
                || longitude < -180
                || longitude > 180) {
            throw badRequest(
                    "Longitude mora biti između -180 i 180"
            );
        }

        if (costPerKm == null
                || costPerKm.compareTo(BigDecimal.ZERO) < 0
                || costPerKm.compareTo(
                BigDecimal.valueOf(1000)
        ) > 0) {
            throw badRequest(
                    "Cena kilometra mora biti između 0 i 1000"
            );
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private record RouteCalculation(
            double totalDistanceKm,
            List<RouteStopResponse> stops
    ) {
    }
}
