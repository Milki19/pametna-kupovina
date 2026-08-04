package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ProductRepository {

    private static final RowMapper<ProductSearchResult>
            PRODUCT_SEARCH_ROW_MAPPER = (resultSet, rowNumber) ->
            new ProductSearchResult(
                    resultSet.getLong("product_id"),
                    resultSet.getString("product_name"),
                    resultSet.getString("brand"),
                    resultSet.getString("barcode"),
                    resultSet.getString("unit"),
                    resultSet.getString("category_name"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getString("retailer_format_name"),
                    resultSet.getObject(
                            "price_date",
                            java.time.LocalDate.class
                    ),
                    resultSet.getBigDecimal("regular_price"),
                    resultSet.getBigDecimal("discounted_price"),
                    resultSet.getBigDecimal("unit_price"),
                    resultSet.getBigDecimal("effective_price")
            );

    private final JdbcClient jdbcClient;

    public ProductRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ProductSearchResult> search(
            String searchText,
            String normalizedSearchText,
            int limit
    ) {
        String searchPattern = "%" + searchText + "%";
        String normalizedSearchPattern =
                "%" + normalizedSearchText + "%";

        return jdbcClient.sql("""
                        SELECT rp.id AS product_id,
                               rp.name AS product_name,
                               rp.brand,
                               rp.barcode,
                               rp.unit,
                               rp.category_name,
                               r.code AS retailer_code,
                               r.name AS retailer_name,
                               price.retailer_format_name,
                               price.price_date,
                               price.regular_price,
                               price.discounted_price,
                               price.unit_price,
                               COALESCE(
                                   price.discounted_price,
                                   price.regular_price
                               ) AS effective_price
                        FROM app.retailer_product rp
                        JOIN app.retailer r
                          ON r.id = rp.retailer_id
                        JOIN LATERAL (
                            SELECT po.retailer_format_name,
                                   po.price_date,
                                   po.regular_price,
                                   po.discounted_price,
                                   po.unit_price
                            FROM app.price_observation po
                            WHERE po.retailer_product_id = rp.id
                            ORDER BY po.price_date DESC,
                                     po.id DESC
                            LIMIT 1
                        ) price ON TRUE
                        WHERE COALESCE(rp.normalized_name, '') LIKE ?
                           OR rp.name ILIKE ?
                           OR COALESCE(rp.brand, '') ILIKE ?
                           OR COALESCE(rp.barcode, '') ILIKE ?
                           OR COALESCE(rp.category_name, '') ILIKE ?
                        ORDER BY effective_price ASC NULLS LAST,
                                 rp.name ASC
                        LIMIT ?
                        """)
                .param(1, normalizedSearchPattern)
                .param(2, searchPattern)
                .param(3, searchPattern)
                .param(4, searchPattern)
                .param(5, searchPattern)
                .param(6, limit)
                .query(PRODUCT_SEARCH_ROW_MAPPER)
                .list();
    }
}
