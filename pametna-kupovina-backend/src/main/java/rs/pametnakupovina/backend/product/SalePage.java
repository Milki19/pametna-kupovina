package rs.pametnakupovina.backend.product;

import java.util.List;

/**
 * @param categories    categories with something on sale under the same
 *                      place and chain, largest first; only on the first page
 * @param nearbyChecked the shopper's location was known, so only chains with
 *                      a shop nearby are in the list
 * @param retailers     chains with something on sale under the same place,
 *                      category and words, most products first; only on the
 *                      first page
 * @param radiusMeters  how far the shops in the list may be; null without a
 *                      location
 */
public record SalePage(
        int page,
        int limit,
        long totalElements,
        int totalPages,
        boolean hasNext,
        List<SaleItem> items,
        List<SaleCategory> categories,
        boolean nearbyChecked,
        List<SaleRetailer> retailers,
        Integer radiusMeters
) {

    public SalePage {
        items = List.copyOf(items);
        categories = List.copyOf(categories);
        retailers = List.copyOf(retailers);
    }
}
