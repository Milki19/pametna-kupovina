package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ShoppingListOptimizationService {

    private final ShoppingListRepository shoppingListRepository;
    private final ShoppingListPricingRepository pricingRepository;
    private final ShoppingListOptimizationRepository
            optimizationRepository;

    public ShoppingListOptimizationService(
            ShoppingListRepository shoppingListRepository,
            ShoppingListPricingRepository pricingRepository,
            ShoppingListOptimizationRepository
                    optimizationRepository
    ) {
        this.shoppingListRepository = shoppingListRepository;
        this.pricingRepository = pricingRepository;
        this.optimizationRepository = optimizationRepository;
    }

    public ShoppingListOptimizationResponse optimize(
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

        List<ShoppingListPriceItem> lowestPriceItems =
                pricingRepository.findBestPrices(listId);

        BigDecimal lowestPriceTotal = BigDecimal.ZERO;
        int matchedItems = 0;
        Set<String> usedStores = new HashSet<>();

        for (ShoppingListPriceItem item : lowestPriceItems) {
            if (!item.matched()) {
                continue;
            }

            matchedItems++;
            lowestPriceTotal = lowestPriceTotal.add(
                    item.lineTotal()
            );

            usedStores.add(item.retailerCode());
        }

        lowestPriceTotal = lowestPriceTotal.setScale(
                2,
                RoundingMode.HALF_UP
        );

        int totalItems = shoppingList.items().size();
        int unmatchedItems = totalItems - matchedItems;

        List<ShoppingListOptimizationRepository.RetailerItemOfferRow>
                offerRows =
                optimizationRepository.findOffersByRetailer(listId);

        Map<String, List<
                ShoppingListOptimizationRepository.RetailerItemOfferRow
                >> rowsByRetailer = new LinkedHashMap<>();

        for (ShoppingListOptimizationRepository.RetailerItemOfferRow
                row : offerRows) {

            if (!rowsByRetailer.containsKey(row.retailerCode())) {
                rowsByRetailer.put(
                        row.retailerCode(),
                        new ArrayList<>()
                );
            }

            rowsByRetailer.get(row.retailerCode()).add(row);
        }

        List<RetailerBasketOption> retailerOptions =
                new ArrayList<>();

        RetailerBasketOption bestSingleStore = null;

        for (List<
                ShoppingListOptimizationRepository.RetailerItemOfferRow
                > retailerRows : rowsByRetailer.values()) {

            if (retailerRows.isEmpty()) {
                continue;
            }

            String retailerCode =
                    retailerRows.getFirst().retailerCode();

            String retailerName =
                    retailerRows.getFirst().retailerName();

            int retailerMatchedItems = 0;
            BigDecimal retailerTotal = BigDecimal.ZERO;

            List<RetailerItemOffer> itemOffers =
                    new ArrayList<>();

            for (ShoppingListOptimizationRepository
                    .RetailerItemOfferRow row : retailerRows) {

                if (row.available()) {
                    retailerMatchedItems++;
                    retailerTotal = retailerTotal.add(
                            row.lineTotal()
                    );
                }

                itemOffers.add(
                        new RetailerItemOffer(
                                row.itemId(),
                                row.requestedName(),
                                row.barcode(),
                                row.quantity(),
                                row.available(),
                                row.productId(),
                                row.productName(),
                                row.priceDate(),
                                row.effectivePrice(),
                                row.lineTotal()
                        )
                );
            }

            retailerTotal = retailerTotal.setScale(
                    2,
                    RoundingMode.HALF_UP
            );

            int missingItems =
                    totalItems - retailerMatchedItems;

            boolean complete = missingItems == 0;

            RetailerBasketOption option =
                    new RetailerBasketOption(
                            retailerCode,
                            retailerName,
                            retailerMatchedItems,
                            missingItems,
                            complete,
                            retailerTotal,
                            itemOffers
                    );

            retailerOptions.add(option);

            if (complete
                    && (
                    bestSingleStore == null
                            || option.totalPrice().compareTo(
                            bestSingleStore.totalPrice()
                    ) < 0
            )) {
                bestSingleStore = option;
            }
        }

        BigDecimal savingsUsingMultipleStores = null;

        if (bestSingleStore != null) {
            savingsUsingMultipleStores =
                    bestSingleStore.totalPrice()
                            .subtract(lowestPriceTotal)
                            .setScale(
                                    2,
                                    RoundingMode.HALF_UP
                            );
        }

        String recommendation;

        if (unmatchedItems > 0) {
            recommendation = "SELECT_PRODUCTS";
        } else if (bestSingleStore == null) {
            recommendation = "MULTI_STORE_REQUIRED";
        } else if (usedStores.size() <= 1) {
            recommendation = "SINGLE_STORE";
        } else if (
                savingsUsingMultipleStores.compareTo(
                        BigDecimal.ZERO
                ) <= 0
        ) {
            recommendation = "SINGLE_STORE";
        } else {
            recommendation = "COMPARE_TRAVEL_COST";
        }

        return new ShoppingListOptimizationResponse(
                shoppingList.id(),
                shoppingList.name(),
                totalItems,
                matchedItems,
                unmatchedItems,
                usedStores.size(),
                lowestPriceTotal,
                bestSingleStore,
                savingsUsingMultipleStores,
                recommendation,
                lowestPriceItems,
                retailerOptions
        );
    }
}