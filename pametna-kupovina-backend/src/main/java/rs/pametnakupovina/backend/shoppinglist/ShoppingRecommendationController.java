package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.privacy.PreciseLocationPolicy;
import rs.pametnakupovina.backend.privacy.PreciseLocationPurpose;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.time.LocalDate;

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
            @PathVariable("listId") Long listId,
            DeviceCaller caller,
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam(
                    name = "date",
                    required = false
            )
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date
    ) {
        shoppingListService.requireOwnedList(listId, caller.accountId());

        return preciseLocationPolicy.useForRequest(
                PreciseLocationPurpose.SHOPPING_LIST_OPTIMIZATION,
                latitude,
                longitude,
                location -> recommendationService.recommend(
                        listId,
                        location.latitude(),
                        location.longitude(),
                        date
                )
        );
    }
}
