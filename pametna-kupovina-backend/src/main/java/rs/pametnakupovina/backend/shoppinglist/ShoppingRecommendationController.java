package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.privacy.PreciseLocationPolicy;
import rs.pametnakupovina.backend.privacy.PreciseLocationPurpose;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/shopping-lists")
public class ShoppingRecommendationController {

    private final ShoppingRecommendationService recommendationService;
    private final ShoppingListService shoppingListService;
    private final PreciseLocationPolicy preciseLocationPolicy;

    public ShoppingRecommendationController(
            ShoppingRecommendationService recommendationService,
            ShoppingListService shoppingListService,
            PreciseLocationPolicy preciseLocationPolicy
    ) {
        this.recommendationService = recommendationService;
        this.shoppingListService = shoppingListService;
        this.preciseLocationPolicy = preciseLocationPolicy;
    }

    @GetMapping("/{listId}/recommendations")
    public ShoppingRecommendationResponse recommend(
            @ListRef Long listId,
            DeviceCaller caller,
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam(
                    name = "date",
                    required = false
            )
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestParam(name = "travelMode", defaultValue = "DRIVING")
            TravelMode travelMode,
            // Without the chains that publish no shop addresses: the web asks
            // for them separately, so the plan shows without waiting for them.
            @RequestParam(name = "unlocated", defaultValue = "true")
            boolean unlocated,
            // "Samo radnje do 1 km", when a plan on foot came out too long.
            @RequestParam(name = "radiusMeters", required = false)
            Integer radiusMeters,
            // Preporuke u dva koraka: every plan's totals and coverage, and
            // the items of this one; another plan's items when it is opened.
            // Without it, every plan with its items (APK 2.0.x).
            @RequestParam(name = "plan", required = false)
            RecommendationScenarioType plan
    ) {
        shoppingListService.requireOwnedList(listId, caller.accountId());

        ShoppingRecommendationResponse answer = preciseLocationPolicy.useForRequest(
                PreciseLocationPurpose.SHOPPING_LIST_OPTIMIZATION,
                latitude,
                longitude,
                location -> recommendationService.recommend(
                        listId,
                        location.latitude(),
                        location.longitude(),
                        date,
                        travelMode,
                        unlocated,
                        radiusMeters
                )
        );
        return plan == null ? answer : answer.detailsOf(plan);
    }

    /** The chains without shop addresses; no location needed. */
    @GetMapping("/{listId}/recommendations/unlocated-price-options")
    public List<UnlocatedPriceOption> unlocatedPriceOptions(
            @ListRef Long listId,
            DeviceCaller caller,
            @RequestParam(
                    name = "date",
                    required = false
            )
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        shoppingListService.requireOwnedList(listId, caller.accountId());

        return recommendationService.unlocatedPriceOptions(listId, date);
    }
}
