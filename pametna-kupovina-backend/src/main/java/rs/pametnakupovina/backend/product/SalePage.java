package rs.pametnakupovina.backend.product;

import java.util.List;

/**
 * @param categories    categories with something on sale under the same
 *                      place and chain, largest first; only on the first page
 * @param nearbyChecked the shopper's location was known, so only chains with
 *                      a shop nearby are in the list
 */
public record SalePage(
        int page,
        int limit,
        long totalElements,
        int totalPages,
        boolean hasNext,
        List<SaleItem> items,
        List<SaleCategory> categories,
        boolean nearbyChecked
) {

    public SalePage {
        items = List.copyOf(items);
        categories = List.copyOf(categories);
    }
}
