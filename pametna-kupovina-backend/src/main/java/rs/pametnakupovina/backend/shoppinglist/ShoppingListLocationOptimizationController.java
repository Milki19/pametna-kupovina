package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/shopping-lists")
public class ShoppingListLocationOptimizationController {

    private final ShoppingListLocationOptimizationService service;

    public ShoppingListLocationOptimizationController(
            ShoppingListLocationOptimizationService service
    ) {
        this.service = service;
    }

    @GetMapping("/{listId}/location-optimization")
    public LocationOptimizationResponse optimize(
            @PathVariable("listId") Long listId,
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam(
                    name = "costPerKm",
                    defaultValue = "20"
            ) BigDecimal costPerKm
    ) {
        return service.optimize(
                listId,
                latitude,
                longitude,
                costPerKm
        );
    }
}