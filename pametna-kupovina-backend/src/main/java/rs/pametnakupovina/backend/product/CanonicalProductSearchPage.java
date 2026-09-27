package rs.pametnakupovina.backend.product;

import java.util.List;

/**
 * @param correctedQuery what was searched for instead, when the query as
 *                       typed found nothing and a misspelled word was put
 *                       right; null when the query was searched as typed
 * @param nearbyChecked  the shopper's location was known, so an item without
 *                       nearestStoreMeters really has no shop nearby
 */
public record CanonicalProductSearchPage(
        String query,
        String correctedQuery,
        int page,
        int limit,
        long totalElements,
        int totalPages,
        boolean hasNext,
        List<CanonicalProductSearchItem> items,
        boolean nearbyChecked
) {

    public CanonicalProductSearchPage {
        items = List.copyOf(items);
    }
}
