package rs.pametnakupovina.backend.product;

/**
 * A chain with something on sale, for filtering the list by it.
 *
 * @param productCount        products on sale in this chain under the same
 *                            place, category and words
 * @param nearestStoreMeters  its closest shop in the circle; null without a
 *                            location
 */
public record SaleRetailer(
        String code,
        String name,
        int productCount,
        Double nearestStoreMeters
) {
}
