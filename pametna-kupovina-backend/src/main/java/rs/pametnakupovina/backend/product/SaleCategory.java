package rs.pametnakupovina.backend.product;

/** A category with something on sale, for filtering the list by it. */
public record SaleCategory(
        String code,
        String name,
        int productCount
) {
}
