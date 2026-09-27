package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shopping-lists")
public class ShoppingListController {

    private final ShoppingListService service;
    private final ShoppingListPricingService pricingService;
    private final ShoppingListOptimizationService optimizationService;
    private final ShoppingListMatchingService matchingService;

    public ShoppingListController(
            ShoppingListService service,
            ShoppingListPricingService pricingService,
            ShoppingListOptimizationService optimizationService,
            ShoppingListMatchingService matchingService
    ) {
        this.service = service;
        this.pricingService = pricingService;
        this.optimizationService = optimizationService;
        this.matchingService = matchingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShoppingListSummary create(
            DeviceCaller caller,
            @RequestBody CreateShoppingListRequest request
    ) {
        return service.create(request, caller.accountId());
    }

    @GetMapping
    public List<ShoppingListSummary> findAll(
            DeviceCaller caller
    ) {
        return service.findAll(caller.accountId());
    }

    @GetMapping("/{listId}")
    public ShoppingListResponse findById(
            @PathVariable("listId") Long listId,
            DeviceCaller caller
    ) {
        return service.findById(listId, caller.accountId());
    }

    @PutMapping("/{listId}")
    public ShoppingListResponse updateList(
            @PathVariable("listId") Long listId,
            DeviceCaller caller,
            @RequestBody UpdateShoppingListRequest request
    ) {
        return service.updateList(listId, caller.accountId(), request);
    }

    @PostMapping("/{listId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ShoppingListItemResponse addItem(
            @PathVariable("listId") Long listId,
            DeviceCaller caller,
            @RequestBody AddShoppingListItemRequest request
    ) {
        return service.addItem(listId, caller.accountId(), request);
    }

    @PostMapping("/{listId}/items/paste")
    @ResponseStatus(HttpStatus.CREATED)
    public PasteShoppingListItemsResponse pasteItems(
            @PathVariable("listId") Long listId,
            DeviceCaller caller,
            @RequestBody PasteShoppingListItemsRequest request
    ) {
        return service.addPastedItems(
                listId,
                caller.accountId(),
                request
        );
    }

    @GetMapping("/{listId}/best-prices")
    public ShoppingListBestPriceResponse calculateBestPrices(
            @PathVariable("listId") Long listId,
            DeviceCaller caller
    ) {
        service.requireOwnedList(listId, caller.accountId());
        return pricingService.calculateBestPrices(listId);
    }

    @PostMapping("/{listId}/matching")
    public ShoppingListMatchingResponse matchItems(
            @PathVariable("listId") Long listId,
            DeviceCaller caller,
            @RequestParam(
                    name = "includeProductsWithoutBarcode",
                    defaultValue = "false"
            ) boolean includeProductsWithoutBarcode
    ) {
        return matchingService.match(
                listId,
                caller,
                includeProductsWithoutBarcode
        );
    }

    @PutMapping("/{listId}/items/{itemId}/match")
    public ShoppingListItemResponse resolveItemMatch(
            @PathVariable("listId") Long listId,
            @PathVariable("itemId") Long itemId,
            DeviceCaller caller,
            @RequestBody ResolveShoppingItemMatchRequest request
    ) {
        return matchingService.resolve(
                listId,
                itemId,
                caller,
                request
        );
    }

    @GetMapping("/{listId}/optimization")
    public ShoppingListOptimizationResponse optimize(
            @PathVariable("listId") Long listId,
            DeviceCaller caller
    ) {
        service.requireOwnedList(listId, caller.accountId());
        return optimizationService.optimize(listId);
    }

    @PutMapping("/{listId}/items/{itemId}")
    public ShoppingListItemResponse updateItem(
            @PathVariable("listId") Long listId,
            @PathVariable("itemId") Long itemId,
            DeviceCaller caller,
            @RequestBody UpdateShoppingListItemRequest request
    ) {
        return service.updateItem(
                listId,
                itemId,
                caller.accountId(),
                request
        );
    }

    @DeleteMapping("/{listId}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(
            @PathVariable("listId") Long listId,
            @PathVariable("itemId") Long itemId,
            DeviceCaller caller
    ) {
        service.deleteItem(listId, itemId, caller.accountId());
    }

    @DeleteMapping("/{listId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteList(
            @PathVariable("listId") Long listId,
            DeviceCaller caller
    ) {
        service.deleteList(listId, caller.accountId());
    }
}
