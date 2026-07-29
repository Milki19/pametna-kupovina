package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shopping-lists")
public class ShoppingListController {

    private final ShoppingListService service;
    private final ShoppingListPricingService pricingService;

    public ShoppingListController(
            ShoppingListService service,
            ShoppingListPricingService pricingService
    ) {
        this.service = service;
        this.pricingService = pricingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShoppingListSummary create(
            @RequestBody CreateShoppingListRequest request
    ) {
        return service.create(request);
    }

    @GetMapping
    public List<ShoppingListSummary> findAll() {
        return service.findAll();
    }

    @GetMapping("/{listId}")
    public ShoppingListResponse findById(
            @PathVariable("listId") Long listId
    ) {
        return service.findById(listId);
    }

    @PostMapping("/{listId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ShoppingListItemResponse addItem(
            @PathVariable("listId") Long listId,
            @RequestBody AddShoppingListItemRequest request
    ) {
        return service.addItem(listId, request);
    }

    @GetMapping("/{listId}/best-prices")
    public ShoppingListBestPriceResponse calculateBestPrices(
            @PathVariable("listId") Long listId
    ) {
        return pricingService.calculateBestPrices(listId);
    }

    @DeleteMapping("/{listId}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(
            @PathVariable("listId") Long listId,
            @PathVariable("itemId") Long itemId
    ) {
        service.deleteItem(listId, itemId);
    }

    @DeleteMapping("/{listId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteList(
            @PathVariable("listId") Long listId
    ) {
        service.deleteList(listId);
    }
}