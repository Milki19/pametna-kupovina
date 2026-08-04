package rs.pametnakupovina.backend.product;

import java.util.List;

public record CanonicalProductSearchPage(
        String query,
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
