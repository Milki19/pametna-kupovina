package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Products on sale today, read from app.current_sale (V115): one row per
 * product with the chain where it comes off the most.
 */
@Repository
public class SaleRepository {

    /** Only shops this close count as "nearby" for the list of sales. */
    static final int NEARBY_METERS = 10_000;

    private static final RowMapper<SaleRow> ROW_MAPPER = (resultSet, rowNumber) ->
            new SaleRow(
                    new SaleItem(
                            resultSet.getLong("product_family_id"),
                            resultSet.getObject("canonical_product_id", Long.class),
                            resultSet.getString("name"),
                            resultSet.getString("brand"),
                            resultSet.getBigDecimal("quantity_value"),
                            resultSet.getString("base_unit"),
                            resultSet.getInt("package_count"),
                            resultSet.getString("category_code"),
                            resultSet.getString("category_name"),
                            resultSet.getString("retailer_code"),
                            resultSet.getString("retailer_name"),
                            resultSet.getBigDecimal("sale_price"),
                            resultSet.getBigDecimal("regular_price"),
                            resultSet.getInt("discount_percent"),
                            resultSet.getObject("discount_end", LocalDate.class),
                            resultSet.getInt("other_chain_count"),
                            resultSet.getObject("meters", Double.class)
                    ),
                    resultSet.getLong("total_count")
            );

    /*
     * The sales a shopper can reach: with a location, only shops within
     * NEARBY_METERS, and a price set for one shop counts only for that shop.
     * Then the best one per product.
     */
    private static final String BEST_SALES = """
            WITH here AS (
                SELECT ST_SetSRID(
                           ST_MakePoint(CAST(:longitude AS DOUBLE PRECISION),
                                        CAST(:latitude AS DOUBLE PRECISION)),
                           4326
                       )::geography AS location
            ),
            nearby_store AS (
                SELECT store.id,
                       store.retailer_id,
                       ST_Distance(store.location, here.location) AS meters
                FROM app.store AS store
                CROSS JOIN here
                WHERE CAST(:located AS BOOLEAN)
                  AND store.active = TRUE
                  AND store.pricing_eligible = TRUE
                  AND store.location IS NOT NULL
                  AND store.geocoding_status IN ('AUTO_VERIFIED', 'MANUALLY_VERIFIED')
                  AND ST_DWithin(store.location, here.location, :radius)
            ),
            nearby_chain AS (
                SELECT retailer_id, MIN(meters) AS meters
                FROM nearby_store
                GROUP BY retailer_id
            ),
            sale AS (
                SELECT sale.*,
                       COALESCE(
                           (SELECT nearby_store.meters
                            FROM nearby_store
                            WHERE nearby_store.id = sale.store_id),
                           nearby_chain.meters
                       ) AS meters
                FROM app.current_sale AS sale
                JOIN app.retailer AS retailer
                  ON retailer.id = sale.retailer_id
                LEFT JOIN nearby_chain
                  ON nearby_chain.retailer_id = sale.retailer_id
                WHERE sale.discount_percent >= :minDiscount
                  AND (CAST(:retailer AS TEXT) IS NULL
                       OR retailer.code = CAST(:retailer AS TEXT))
                  AND (
                      NOT CAST(:located AS BOOLEAN)
                      OR (sale.scope_type = 'STORE'
                          AND sale.store_id IN (SELECT id FROM nearby_store))
                      OR (sale.scope_type <> 'STORE'
                          AND nearby_chain.retailer_id IS NOT NULL)
                  )
            ),
            chain_count AS (
                SELECT product_family_id,
                       COUNT(DISTINCT retailer_id)::INTEGER AS chains
                FROM sale
                GROUP BY product_family_id
            ),
            best AS (
                SELECT DISTINCT ON (sale.product_family_id) sale.*
                FROM sale
                ORDER BY sale.product_family_id,
                         sale.discount_percent DESC,
                         sale.sale_price ASC,
                         sale.meters ASC NULLS LAST
            ),
            listed AS (
                SELECT best.*,
                       chain_count.chains,
                       family.id AS family_id,
                       family.normalized_name,
                       category.code AS category_code,
                       category.name AS category_name
                FROM best
                JOIN chain_count
                  ON chain_count.product_family_id = best.product_family_id
                JOIN app.product_family AS family
                  ON family.id = best.product_family_id
                LEFT JOIN app.product_category AS category
                  ON category.id = family.product_category_id
                WHERE family.review_status <> 'REJECTED'
                  AND (CAST(:query AS TEXT) IS NULL
                       OR family.normalized_name LIKE '%' || CAST(:query AS TEXT) || '%')
            )
            """;

    private final JdbcClient jdbcClient;

    public SaleRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<SaleRow> findSales(SaleFilter filter, SaleSort sort, int limit, long offset) {
        String order = switch (sort) {
            case DISCOUNT -> "listed.discount_percent DESC, listed.regular_price - listed.sale_price DESC";
            case SAVING -> "listed.regular_price - listed.sale_price DESC, listed.discount_percent DESC";
            case PRICE -> "listed.sale_price ASC, listed.discount_percent DESC";
        };
        return withFilter(jdbcClient.sql(BEST_SALES + """
                        SELECT listed.product_family_id,
                               listed.canonical_product_id,
                               COALESCE(family.composed_name, family.display_name) AS name,
                               brand.display_name AS brand,
                               family.quantity_value,
                               family.base_unit,
                               COALESCE((
                                   SELECT MAX(member_product.package_count)
                                   FROM app.retailer_product AS member_product
                                   WHERE member_product.product_family_id = family.id
                                     AND member_product.quantity_value = family.quantity_value
                               ), 1) AS package_count,
                               listed.category_code,
                               listed.category_name,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               listed.sale_price,
                               listed.regular_price,
                               listed.discount_percent,
                               listed.discount_end,
                               listed.chains - 1 AS other_chain_count,
                               listed.meters,
                               COUNT(*) OVER () AS total_count
                        FROM listed
                        JOIN app.product_family AS family
                          ON family.id = listed.family_id
                        LEFT JOIN app.brand AS brand
                          ON brand.id = family.brand_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = listed.retailer_id
                        WHERE CAST(:category AS TEXT) IS NULL
                           OR listed.category_code = CAST(:category AS TEXT)
                        ORDER BY %s,
                                 family.id
                        LIMIT :limit OFFSET :offset
                        """.formatted(order)), filter)
                .param("limit", limit)
                .param("offset", offset)
                .query(ROW_MAPPER)
                .list();
    }

    /** Categories with something on sale under the same place and chain. */
    public List<SaleCategory> findCategories(SaleFilter filter) {
        return withFilter(jdbcClient.sql(BEST_SALES + """
                        SELECT listed.category_code,
                               listed.category_name,
                               COUNT(*)::INTEGER AS product_count
                        FROM listed
                        WHERE listed.category_code IS NOT NULL
                        GROUP BY listed.category_code, listed.category_name
                        ORDER BY product_count DESC, listed.category_name
                        """), filter)
                .query((resultSet, rowNumber) -> new SaleCategory(
                        resultSet.getString("category_code"),
                        resultSet.getString("category_name"),
                        resultSet.getInt("product_count")
                ))
                .list();
    }

    private static JdbcClient.StatementSpec withFilter(
            JdbcClient.StatementSpec statement,
            SaleFilter filter
    ) {
        return statement
                .param("located", filter.located())
                .param("latitude", filter.located() ? filter.latitude() : null)
                .param("longitude", filter.located() ? filter.longitude() : null)
                .param("radius", NEARBY_METERS)
                .param("minDiscount", filter.minDiscount())
                .param("retailer", filter.retailerCode())
                .param("category", filter.categoryCode())
                .param("query", filter.normalizedQuery());
    }

    /**
     * @param normalizedQuery words the product's name must contain, already
     *                        normalized the way names are; null for any
     */
    record SaleFilter(
            Double latitude,
            Double longitude,
            String categoryCode,
            String retailerCode,
            String normalizedQuery,
            int minDiscount
    ) {
        boolean located() {
            return latitude != null && longitude != null;
        }
    }

    record SaleRow(SaleItem item, long totalCount) {
    }
}
