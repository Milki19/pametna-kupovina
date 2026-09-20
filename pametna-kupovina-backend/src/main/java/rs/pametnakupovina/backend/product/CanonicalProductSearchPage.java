package rs.pametnakupovina.backend.product;

import java.util.List;

/**
 * @param correctedQuery what was searched for instead, when the query as
 *                       typed found nothing and a misspelled word was put
 *                       right; null when the query was searched as typed
 */
public record CanonicalProductSearchPage(
        String query,
        String correctedQuery,
        int page,
        int limit,
        long totalElements,
        int totalPages,
        boolean hasNext,
        List<CanonicalProductSearchItem> items
) {

    public CanonicalProductSearchPage {
        items = List.copyOf(items);
    }
}
