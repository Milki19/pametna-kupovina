package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.routing.RouteMatrix;
import rs.pametnakupovina.backend.routing.RouteMatrixEntry;
import rs.pametnakupovina.backend.routing.RouteMatrixProvider;
import rs.pametnakupovina.backend.routing.RouteWaypoint;
import rs.pametnakupovina.backend.store.NearbyStore;
import rs.pametnakupovina.backend.store.NearbyStoreRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ShoppingRecommendationService {

    private static final String USER_WAYPOINT_ID = "USER";
    private static final String CURRENCY = "RSD";
    private static final String DISCLAIMER =
            "Prikazane cene su iz cenovnika za navedeni datum; "
                    + "zalihe i cena na kasi nisu garantovane.";

    private final ShoppingListRepository shoppingListRepository;
    private final NearbyStoreRepository nearbyStoreRepository;
    private final StoreShoppingOfferRepository offerRepository;
    private final RouteMatrixProvider routeMatrixProvider;
    private final ShoppingOptimizationProperties properties;

    public ShoppingRecommendationService(
            ShoppingListRepository shoppingListRepository,
            NearbyStoreRepository nearbyStoreRepository,
            StoreShoppingOfferRepository offerRepository,
            RouteMatrixProvider routeMatrixProvider,
            ShoppingOptimizationProperties properties
    ) {
        this.shoppingListRepository = shoppingListRepository;
        this.nearbyStoreRepository = nearbyStoreRepository;
        this.offerRepository = offerRepository;
        this.routeMatrixProvider = routeMatrixProvider;
        this.properties = properties;
    }

    public ShoppingRecommendationResponse recommend(
            Long listId,
            double latitude,
            double longitude,
            LocalDate asOfDate
    ) {
        validate(latitude, longitude, asOfDate);

        ShoppingListResponse shoppingList = shoppingListRepository
                .findById(listId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Spisak nije pronađen: " + listId
                ));

        List<Long> blockingItemIds = shoppingList.items().stream()
                .filter(item -> item.matchingRule()
                        == ShoppingItemRule.EXACT_PRODUCT)
                .filter(item -> item.matchingStatus()
                        == ShoppingItemMatchingStatus.PENDING
                        || item.matchingStatus()
                        == ShoppingItemMatchingStatus.NEEDS_CONFIRMATION)
                .map(ShoppingListItemResponse::id)
                .toList();

        if (!blockingItemIds.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Potvrdite važne stavke pre optimizacije: "
                            + blockingItemIds
            );
        }

        List<NearbyStore> nearbyStores =
                nearbyStoreRepository.findNearby(
                        latitude,
                        longitude,
                        properties.getCandidateRadiusMeters(),
                        properties.getMaxCandidateStores()
                );

        List<Long> storeIds = nearbyStores.stream()
                .map(NearbyStore::storeId)
                .toList();

        List<StoreItemOffer> offerRows = offerRepository.findOffers(
                listId,
                storeIds,
                asOfDate
        );

        Map<Long, Map<Long, StoreItemOffer>> offersByStore =
                groupOffersByStore(offerRows);

        List<CandidatePlan> singleStorePlans = new ArrayList<>();

        for (NearbyStore store : nearbyStores) {
            singleStorePlans.add(
                    createPlan(
                            List.of(store),
                            shoppingList.items(),
                            offersByStore
                    )
            );
        }

        List<CandidatePlan> twoStorePlans = new ArrayList<>();

        for (int first = 0;
             first < nearbyStores.size();
             first++) {
            for (int second = first + 1;
                 second < nearbyStores.size();
                 second++) {
                twoStorePlans.add(
                        createPlan(
                                List.of(
                                        nearbyStores.get(first),
                                        nearbyStores.get(second)
                                ),
                                shoppingList.items(),
                                offersByStore
                        )
                );
            }
        }

        RouteMatrix routeMatrix = createRouteMatrix(
                latitude,
                longitude,
                nearbyStores
        );

        List<EvaluatedPlan> evaluatedSingles =
                singleStorePlans.stream()
                        .map(plan -> evaluate(plan, routeMatrix))
                        .toList();

        List<EvaluatedPlan> evaluatedAll = new ArrayList<>(
                evaluatedSingles
        );

        evaluatedAll.addAll(
                twoStorePlans.stream()
                        .map(plan -> evaluate(plan, routeMatrix))
                        .toList()
        );

        EvaluatedPlan bestSingle = chooseByBasket(evaluatedSingles);
        EvaluatedPlan lowestPrice = chooseByBasket(evaluatedAll);
        EvaluatedPlan recommended = chooseByTotalCost(evaluatedAll);

        OptimizationScenarioResponse singleScenario = buildScenario(
                RecommendationScenarioType.SINGLE_STORE,
                bestSingle,
                bestSingle,
                shoppingList.items(),
                routeMatrix
        );

        OptimizationScenarioResponse recommendedScenario = buildScenario(
                RecommendationScenarioType.RECOMMENDED_BALANCE,
                recommended,
                bestSingle,
                shoppingList.items(),
                routeMatrix
        );

        OptimizationScenarioResponse lowestPriceScenario = buildScenario(
                RecommendationScenarioType.LOWEST_PRICE,
                lowestPrice,
                bestSingle,
                shoppingList.items(),
                routeMatrix
        );

        return new ShoppingRecommendationResponse(
                shoppingList.id(),
                shoppingList.name(),
                asOfDate,
                nearbyStores.size(),
                singleStorePlans.size(),
                twoStorePlans.size(),
                assumptions(),
                singleScenario,
                recommendedScenario,
                lowestPriceScenario,
                DISCLAIMER
        );
    }

    private Map<Long, Map<Long, StoreItemOffer>> groupOffersByStore(
            List<StoreItemOffer> offerRows
    ) {
        Map<Long, Map<Long, StoreItemOffer>> grouped =
                new LinkedHashMap<>();

        for (StoreItemOffer row : offerRows) {
            grouped.computeIfAbsent(
                    row.storeId(),
                    ignored -> new LinkedHashMap<>()
            ).put(row.itemId(), row);
        }

        return grouped;
    }

    private CandidatePlan createPlan(
            List<NearbyStore> stores,
            List<ShoppingListItemResponse> items,
            Map<Long, Map<Long, StoreItemOffer>> offersByStore
    ) {
        Map<Long, StoreItemOffer> assignments =
                new LinkedHashMap<>();

        BigDecimal basketCost = BigDecimal.ZERO;

        for (ShoppingListItemResponse item : items) {
            StoreItemOffer cheapest = null;

            for (NearbyStore store : stores) {
                StoreItemOffer candidate = offersByStore
                        .getOrDefault(store.storeId(), Map.of())
                        .get(item.id());

                if (candidate == null || !candidate.available()) {
                    continue;
                }

                if (cheapest == null
                        || candidate.lineTotal().compareTo(
                        cheapest.lineTotal()
                ) < 0
                        || (
                        candidate.lineTotal().compareTo(
                                cheapest.lineTotal()
                        ) == 0
                                && candidate.storeId()
                                < cheapest.storeId()
                )) {
                    cheapest = candidate;
                }
            }

            if (cheapest != null) {
                assignments.put(item.id(), cheapest);
                basketCost = basketCost.add(cheapest.lineTotal());
            }
        }

        return new CandidatePlan(
                List.copyOf(stores),
                Map.copyOf(assignments),
                assignments.size(),
                money(basketCost)
        );
    }

    private EvaluatedPlan evaluate(
            CandidatePlan plan,
            RouteMatrix routeMatrix
    ) {
        RouteEvaluation route = evaluateRoute(
                plan.stores(),
                routeMatrix
        );

        BigDecimal travelCost = money(
                BigDecimal.valueOf(route.distanceKm())
                        .multiply(properties.getCostPerKm())
        );

        BigDecimal timeCost = money(
                BigDecimal.valueOf(route.durationSeconds())
                        .divide(
                                BigDecimal.valueOf(3600),
                                8,
                                RoundingMode.HALF_UP
                        )
                        .multiply(properties.getValuePerHour())
        );

        BigDecimal stopCost = money(
                properties.getCostPerStop().multiply(
                        BigDecimal.valueOf(plan.stores().size())
                )
        );

        BigDecimal totalCost = money(
                plan.basketCost()
                        .add(travelCost)
                        .add(timeCost)
                        .add(stopCost)
        );

        return new EvaluatedPlan(
                plan,
                route.orderedStores(),
                route.stops(),
                route.distanceKm(),
                route.durationSeconds(),
                travelCost,
                timeCost,
                stopCost,
                totalCost
        );
    }

    private RouteEvaluation evaluateRoute(
            List<NearbyStore> stores,
            RouteMatrix routeMatrix
    ) {
        if (stores.size() == 1) {
            return routeForOrder(stores, routeMatrix);
        }

        RouteEvaluation firstOrder = routeForOrder(
                stores,
                routeMatrix
        );

        RouteEvaluation secondOrder = routeForOrder(
                List.of(stores.get(1), stores.get(0)),
                routeMatrix
        );

        if (secondOrder.durationSeconds()
                < firstOrder.durationSeconds()) {
            return secondOrder;
        }

        if (secondOrder.durationSeconds()
                == firstOrder.durationSeconds()
                && secondOrder.distanceKm()
                < firstOrder.distanceKm()) {
            return secondOrder;
        }

        return firstOrder;
    }

    private RouteEvaluation routeForOrder(
            List<NearbyStore> stores,
            RouteMatrix routeMatrix
    ) {
        String currentWaypointId = USER_WAYPOINT_ID;
        double totalDistanceKm = 0;
        long totalDurationSeconds = 0;
        List<RecommendationStoreResponse> stops = new ArrayList<>();

        for (int index = 0; index < stores.size(); index++) {
            NearbyStore store = stores.get(index);
            String storeWaypointId = waypointId(store);
            RouteMatrixEntry leg = routeMatrix.entry(
                    currentWaypointId,
                    storeWaypointId
            );

            double legDistanceKm =
                    leg.distanceMeters() / 1000.0;
            long legDurationSeconds = durationSeconds(
                    leg,
                    legDistanceKm
            );

            totalDistanceKm += legDistanceKm;
            totalDurationSeconds += legDurationSeconds;

            stops.add(
                    new RecommendationStoreResponse(
                            index + 1,
                            store.storeId(),
                            store.retailerCode(),
                            store.retailerName(),
                            store.storeFormatCode(),
                            store.storeFormatName(),
                            store.storeName(),
                            store.address(),
                            store.city(),
                            store.latitude(),
                            store.longitude(),
                            roundDistance(legDistanceKm),
                            legDurationSeconds
                    )
            );

            currentWaypointId = storeWaypointId;
        }

        RouteMatrixEntry returnLeg = routeMatrix.entry(
                currentWaypointId,
                USER_WAYPOINT_ID
        );

        double returnDistanceKm =
                returnLeg.distanceMeters() / 1000.0;

        totalDistanceKm += returnDistanceKm;
        totalDurationSeconds += durationSeconds(
                returnLeg,
                returnDistanceKm
        );

        return new RouteEvaluation(
                List.copyOf(stores),
                List.copyOf(stops),
                roundDistance(totalDistanceKm),
                totalDurationSeconds
        );
    }

    private long durationSeconds(
            RouteMatrixEntry entry,
            double distanceKm
    ) {
        if (entry.durationSeconds() != null) {
            return entry.durationSeconds();
        }

        return Math.round(
                distanceKm
                        / properties
                        .getStraightLineAverageSpeedKmh()
                        .doubleValue()
                        * 3600.0
        );
    }

    private EvaluatedPlan chooseByBasket(
            Collection<EvaluatedPlan> plans
    ) {
        EvaluatedPlan best = null;

        for (EvaluatedPlan candidate : plans) {
            if (candidate.plan().coveredItems() == 0) {
                continue;
            }

            if (best == null
                    || candidate.plan().coveredItems()
                    > best.plan().coveredItems()
                    || (
                    candidate.plan().coveredItems()
                            == best.plan().coveredItems()
                            && candidate.plan().basketCost().compareTo(
                            best.plan().basketCost()
                    ) < 0
            )
                    || (
                    candidate.plan().coveredItems()
                            == best.plan().coveredItems()
                            && candidate.plan().basketCost().compareTo(
                            best.plan().basketCost()
                    ) == 0
                            && candidate.totalCost().compareTo(
                            best.totalCost()
                    ) < 0
            )) {
                best = candidate;
            }
        }

        return best;
    }

    private EvaluatedPlan chooseByTotalCost(
            Collection<EvaluatedPlan> plans
    ) {
        EvaluatedPlan best = null;

        for (EvaluatedPlan candidate : plans) {
            if (candidate.plan().coveredItems() == 0) {
                continue;
            }

            if (best == null
                    || candidate.plan().coveredItems()
                    > best.plan().coveredItems()
                    || (
                    candidate.plan().coveredItems()
                            == best.plan().coveredItems()
                            && candidate.totalCost().compareTo(
                            best.totalCost()
                    ) < 0
            )
                    || (
                    candidate.plan().coveredItems()
                            == best.plan().coveredItems()
                            && candidate.totalCost().compareTo(
                            best.totalCost()
                    ) == 0
                            && candidate.plan().stores().size()
                            < best.plan().stores().size()
            )) {
                best = candidate;
            }
        }

        return best;
    }

    private OptimizationScenarioResponse buildScenario(
            RecommendationScenarioType type,
            EvaluatedPlan evaluated,
            EvaluatedPlan bestSingle,
            List<ShoppingListItemResponse> shoppingItems,
            RouteMatrix routeMatrix
    ) {
        Map<Long, StoreItemOffer> assignments = evaluated == null
                ? Map.of()
                : evaluated.plan().assignments();

        List<RecommendationItemResponse> itemResponses =
                new ArrayList<>();

        int unmatchedItems = 0;
        int unavailableItems = 0;

        for (ShoppingListItemResponse item : shoppingItems) {
            StoreItemOffer offer = assignments.get(item.id());

            if (offer != null && offer.available()) {
                itemResponses.add(availableItem(item, offer));
                continue;
            }

            RecommendationItemStatus status;
            String explanation;

            if (item.matchingStatus()
                    == ShoppingItemMatchingStatus.NEEDS_CONFIRMATION
                    || item.matchingStatus()
                    == ShoppingItemMatchingStatus.PENDING) {
                status = RecommendationItemStatus.NEEDS_CONFIRMATION;
                explanation = "Stavka čeka potvrdu uparivanja.";
                unmatchedItems++;
            } else if (item.matchingRule()
                    == ShoppingItemRule.EXACT_PRODUCT
                    && item.matchingStatus()
                    == ShoppingItemMatchingStatus.UNMATCHED) {
                status = RecommendationItemStatus.UNMATCHED;
                explanation = "Stavka nije povezana sa proizvodom.";
                unmatchedItems++;
            } else {
                status = RecommendationItemStatus.NO_VALID_PRICE;
                explanation =
                        "Nema važeće cene u izabranim prodavnicama za traženi datum.";
                unavailableItems++;
            }

            itemResponses.add(
                    unavailableItem(item, status, explanation)
            );
        }

        int coveredItems = (int) itemResponses.stream()
                .filter(item -> item.resultStatus()
                        == RecommendationItemStatus.AVAILABLE)
                .count();

        boolean available = evaluated != null && coveredItems > 0;
        boolean complete = available
                && coveredItems == shoppingItems.size();

        Set<String> sources = new LinkedHashSet<>();

        for (RecommendationItemResponse item : itemResponses) {
            if (item.resultStatus()
                    == RecommendationItemStatus.AVAILABLE) {
                sources.add(item.retailerCode());
            }
        }

        LocalDate dataAsOf = itemResponses.stream()
                .map(RecommendationItemResponse::priceDate)
                .filter(value -> value != null)
                .min(Comparator.naturalOrder())
                .orElse(null);

        BigDecimal savings = null;

        if (available && bestSingle != null) {
            savings = money(
                    bestSingle.totalCost().subtract(
                            evaluated.totalCost()
                    )
            );
        }

        return new OptimizationScenarioResponse(
                type,
                available,
                complete,
                scenarioExplanation(
                        type,
                        available,
                        complete,
                        unmatchedItems,
                        unavailableItems
                ),
                coveredItems,
                unmatchedItems,
                unavailableItems,
                evaluated == null
                        ? 0
                        : evaluated.plan().stores().size(),
                evaluated == null
                        ? BigDecimal.ZERO.setScale(2)
                        : evaluated.plan().basketCost(),
                evaluated == null
                        ? 0
                        : evaluated.distanceKm(),
                evaluated == null
                        ? 0
                        : evaluated.durationSeconds(),
                evaluated == null
                        ? BigDecimal.ZERO.setScale(2)
                        : evaluated.travelCost(),
                evaluated == null
                        ? BigDecimal.ZERO.setScale(2)
                        : evaluated.timeCost(),
                evaluated == null
                        ? BigDecimal.ZERO.setScale(2)
                        : evaluated.stopCost(),
                evaluated == null ? null : evaluated.totalCost(),
                savings,
                routeMatrix.providerCode(),
                routeMatrix.distanceMethod(),
                routeMatrix.approximate(),
                List.copyOf(sources),
                dataAsOf,
                evaluated == null
                        ? List.of()
                        : evaluated.stops(),
                itemResponses,
                DISCLAIMER
        );
    }

    private RecommendationItemResponse availableItem(
            ShoppingListItemResponse item,
            StoreItemOffer offer
    ) {
        return new RecommendationItemResponse(
                item.id(),
                item.name(),
                item.quantity(),
                item.matchingRule(),
                item.matchingStatus(),
                RecommendationItemStatus.AVAILABLE,
                offer.storeId(),
                offer.retailerCode(),
                offer.retailerName(),
                offer.canonicalProductId(),
                offer.retailerProductId(),
                offer.productName(),
                offer.productBrand(),
                offer.productBarcode(),
                offer.priceDate(),
                offer.effectivePrice(),
                offer.lineTotal(),
                offer.priceScope(),
                "Važeća cena je izabrana po scope prioritetu "
                        + "objekat, format, pa lanac."
        );
    }

    private RecommendationItemResponse unavailableItem(
            ShoppingListItemResponse item,
            RecommendationItemStatus status,
            String explanation
    ) {
        return new RecommendationItemResponse(
                item.id(),
                item.name(),
                item.quantity(),
                item.matchingRule(),
                item.matchingStatus(),
                status,
                null,
                null,
                null,
                item.matchedCanonicalProductId(),
                null,
                null,
                null,
                item.barcode(),
                null,
                null,
                null,
                null,
                explanation
        );
    }

    private String scenarioExplanation(
            RecommendationScenarioType type,
            boolean available,
            boolean complete,
            int unmatchedItems,
            int unavailableItems
    ) {
        if (!available) {
            return "Scenario nije dostupan jer nema važećih ponuda u radijusu.";
        }

        String base = switch (type) {
            case SINGLE_STORE ->
                    "Najjeftinija poznata korpa u jednoj obližnjoj prodavnici.";
            case RECOMMENDED_BALANCE ->
                    "Najbolji balans cene korpe, puta, vremena i broja stajanja.";
            case LOWEST_PRICE ->
                    "Najniža cena korpe među svim jednom i dvema prodavnicama; put nije kriterijum rangiranja.";
        };

        if (complete) {
            return base;
        }

        return base
                + " Rezultat nije kompletan: "
                + unmatchedItems
                + " neuparenih i "
                + unavailableItems
                + " stavki bez važeće cene.";
    }

    private RouteMatrix createRouteMatrix(
            double latitude,
            double longitude,
            List<NearbyStore> stores
    ) {
        List<RouteWaypoint> waypoints = new ArrayList<>();

        waypoints.add(
                new RouteWaypoint(
                        USER_WAYPOINT_ID,
                        latitude,
                        longitude
                )
        );

        for (NearbyStore store : stores) {
            waypoints.add(
                    new RouteWaypoint(
                            waypointId(store),
                            store.latitude(),
                            store.longitude()
                    )
            );
        }

        return routeMatrixProvider.calculate(waypoints);
    }

    private String waypointId(NearbyStore store) {
        return "STORE:" + store.storeId();
    }

    private OptimizationAssumptions assumptions() {
        return new OptimizationAssumptions(
                properties.getCandidateRadiusMeters(),
                properties.getMaxCandidateStores(),
                money(properties.getCostPerKm()),
                money(properties.getValuePerHour()),
                money(properties.getCostPerStop()),
                money(properties.getStraightLineAverageSpeedKmh()),
                CURRENCY
        );
    }

    private void validate(
            double latitude,
            double longitude,
            LocalDate asOfDate
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

        if (asOfDate == null) {
            throw badRequest("Datum obračuna je obavezan");
        }

        if (asOfDate.isAfter(LocalDate.now())) {
            throw badRequest(
                    "Datum obračuna ne može biti u budućnosti"
            );
        }

        if (properties.getCandidateRadiusMeters() <= 0
                || properties.getMaxCandidateStores() <= 0
                || properties.getMaxCandidateStores() > 20
                || negative(properties.getCostPerKm())
                || negative(properties.getValuePerHour())
                || negative(properties.getCostPerStop())
                || properties.getStraightLineAverageSpeedKmh() == null
                || properties.getStraightLineAverageSpeedKmh()
                .compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException(
                    "Shopping optimization konfiguracija nije ispravna"
            );
        }
    }

    private boolean negative(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private double roundDistance(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record CandidatePlan(
            List<NearbyStore> stores,
            Map<Long, StoreItemOffer> assignments,
            int coveredItems,
            BigDecimal basketCost
    ) {
    }

    private record EvaluatedPlan(
            CandidatePlan plan,
            List<NearbyStore> orderedStores,
            List<RecommendationStoreResponse> stops,
            double distanceKm,
            long durationSeconds,
            BigDecimal travelCost,
            BigDecimal timeCost,
            BigDecimal stopCost,
            BigDecimal totalCost
    ) {
    }

    private record RouteEvaluation(
            List<NearbyStore> orderedStores,
            List<RecommendationStoreResponse> stops,
            double distanceKm,
            long durationSeconds
    ) {
    }
}
