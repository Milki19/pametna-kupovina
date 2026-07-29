package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ShoppingListPricingService {

    private final ShoppingListRepository shoppingListRepository;
    private final ShoppingListPricingRepository pricingRepository;

    public ShoppingListPricingService(
            ShoppingListRepository shoppingListRepository,
            ShoppingListPricingRepository pricingRepository
    ) {
        this.shoppingListRepository = shoppingListRepository;
        this.pricingRepository = pricingRepository;
    }

    public ShoppingListBestPriceResponse calculateBestPrices(
            Long listId
    ) {
        ShoppingListResponse shoppingList =
                shoppingListRepository.findById(listId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Spisak nije pronađen: " + listId
                                )
                        );

        List<ShoppingListPriceItem> items =
                pricingRepository.findBestPrices(listId);

        int matchedItems = (int) items.stream()
                .filter(ShoppingListPriceItem::matched)
                .count();

        int unmatchedItems = items.size() - matchedItems;

        BigDecimal totalPrice = items.stream()
                .map(ShoppingListPriceItem::lineTotal)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);

        return new ShoppingListBestPriceResponse(
                shoppingList.id(),
                shoppingList.name(),
                matchedItems,
                unmatchedItems,
                totalPrice,
                items
        );
    }
}