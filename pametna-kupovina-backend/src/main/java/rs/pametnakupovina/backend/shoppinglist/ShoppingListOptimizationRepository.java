package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class ShoppingListOptimizationRepository {

    private static final RowMapper<RetailerItemOfferRow>
            OFFER_ROW_MAPPER = (resultSet, rowNumber) ->
            new RetailerItemOfferRow(
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getLong("item_id"),
                    resultSet.getString("requested_name"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity"),
                    resultSet.getObject(
                            "product_id",
                            Long.class
                    ),
                    resultSet.getString("product_name"),
                    resultSet.getObject(
                            "price_date",
                            LocalDate.class
                    ),
                    resultSet.getBigDecimal("effective_price"),
                    resultSet.getBigDecimal("line_total")
            );

    private final JdbcClient jdbcClient;

    public ShoppingListOptimizationRepository(
            JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    public List<RetailerItemOfferRow> findOffersByRetailer(
            Long listId
    ) {
        return jdbcClient.sql("""
                        SELECT r.code AS retailer_code,
                               r.name AS retailer_name,
                               sli.id AS item_id,
                               sli.name AS requested_name,
                               sli.barcode,
                               sli.quantity,
                               rp.id AS product_id,
                               rp.name AS product_name,
                               latest_price.price_date,
                               latest_price.effective_price,
                               ROUND(
                                   latest_price.effective_price
                                       * sli.quantity,
                                   2
                               ) AS line_total
                        FROM app.retailer r
                        CROSS JOIN app.shopping_list_item sli
                        LEFT JOIN app.retailer_product rp
                          ON rp.retailer_id = r.id
                         AND sli.barcode IS NOT NULL
                         AND rp.barcode = sli.barcode
                        LEFT JOIN LATERAL (
                            SELECT po.price_date,
                                   COALESCE(
                                       CASE
                                           WHEN po.discounted_price IS NOT NULL
                                            AND (
                                                po.discount_start IS NULL
                                                OR po.discount_start
                                                    <= po.price_date
                                            )
                                            AND (
                                                po.discount_end IS NULL
                                                OR po.discount_end
                                                    >= po.price_date
                                            )
                                           THEN po.discounted_price
                                       END,
                                       po.regular_price,
                                       po.discounted_price
                                   ) AS effective_price
                            FROM app.price_observation po
                            WHERE po.retailer_product_id = rp.id
                            ORDER BY po.price_date DESC,
                                     po.id DESC
                            LIMIT 1
                        ) latest_price ON rp.id IS NOT NULL
                        WHERE sli.shopping_list_id = ?
                        ORDER BY r.code ASC,
                                 sli.id ASC
                        """)
                .param(1, listId)
                .query(OFFER_ROW_MAPPER)
                .list();
    }

    record RetailerItemOfferRow(
            String retailerCode,
            String retailerName,
            Long itemId,
            String requestedName,
            String barcode,
            java.math.BigDecimal quantity,
            Long productId,
            String productName,
            LocalDate priceDate,
            java.math.BigDecimal effectivePrice,
            java.math.BigDecimal lineTotal
    ) {
        boolean available() {
            return productId != null && effectivePrice != null;
        }
    }
}