package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.privacy.PreciseLocationPolicy;
import rs.pametnakupovina.backend.privacy.PreciseLocationPurpose;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/shopping-lists")
public class ShoppingListLocationOptimizationController {

    private final ShoppingListLocationOptimizationService service;

    private final ShoppingListService shoppingListService;

    private final PreciseLocationPolicy preciseLocationPolicy;

    public ShoppingListLocationOptimizationController(
            ShoppingListLocationOptimizationService service,
            ShoppingListService shoppingListService,
            PreciseLocationPolicy preciseLocationPolicy
    ) {
        this.service = service;
        this.shoppingListService = shoppingListService;
        this.preciseLocationPolicy = preciseLocationPolicy;
    }

    @GetMapping("/{listId}/location-optimization")
    public LocationOptimizationResponse optimize(
            @PathVariable("listId") Long listId,
            DeviceCaller caller,
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam(
                    name = "costPerKm",
                    required = false
            ) BigDecimal costPerKm
    ) {
        shoppingListService.requireOwnedList(listId, caller.accountId());

        return preciseLocationPolicy.useForRequest(
                PreciseLocationPurpose.SHOPPING_LIST_OPTIMIZATION,
                latitude,
                longitude,
                location -> service.optimize(
                        listId,
                        location.latitude(),
                        location.longitude(),
                        costPerKm
                )
        );
    }
}
