package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class ShoppingListPricingRepository {

    private static final RowMapper<ShoppingListPriceItem>
            PRICE_ITEM_ROW_MAPPER = (resultSet, rowNumber) -> {

        Long productId = resultSet.getObject(
                "product_id",
                Long.class
        );

        return new ShoppingListPriceItem(
                resultSet.getLong("item_id"),
                resultSet.getString("requested_name"),
                resultSet.getString("barcode"),
                resultSet.getBigDecimal("quantity"),
                productId != null,
                productId,
                resultSet.getString("product_name"),
                resultSet.getString("retailer_code"),
                resultSet.getString("retailer_name"),
                resultSet.getObject(
                        "price_date",
                        LocalDate.class
                ),
                resultSet.getBigDecimal("regular_price"),
                resultSet.getBigDecimal("discounted_price"),
                resultSet.getBigDecimal("effective_price"),
                resultSet.getBigDecimal("line_total")
        );
    };

    private final JdbcClient jdbcClient;

    public ShoppingListPricingRepository(
            JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    public List<ShoppingListPriceItem> findBestPrices(
            Long listId
    ) {
        return jdbcClient.sql("""
                        SELECT sli.id AS item_id,
                               sli.name AS requested_name,
                               sli.barcode,
                               sli.quantity,
                               offer.product_id,
                               offer.product_name,
                               offer.retailer_code,
                               offer.retailer_name,
                               offer.price_date,
                               offer.regular_price,
                               offer.discounted_price,
                               offer.effective_price,
                               ROUND(
                                   offer.effective_price * sli.quantity,
                                   2
                               ) AS line_total
                        FROM app.shopping_list_item sli
                        LEFT JOIN LATERAL (
                            SELECT rp.id AS product_id,
                                   rp.name AS product_name,
                                   r.code AS retailer_code,
                                   r.name AS retailer_name,
                                   latest_price.price_date,
                                   latest_price.regular_price,
                                   latest_price.discounted_price,
                                   COALESCE(
                                       CASE
                                           WHEN latest_price.discounted_price
                                                    IS NOT NULL
                                            AND (
                                                latest_price.discount_start
                                                    IS NULL
                                                OR latest_price.discount_start
                                                    <= latest_price.price_date
                                            )
                                            AND (
                                                latest_price.discount_end
                                                    IS NULL
                                                OR latest_price.discount_end
                                                    >= latest_price.price_date
                                            )
                                           THEN latest_price.discounted_price
                                       END,
                                       latest_price.regular_price,
                                       latest_price.discounted_price
                                   ) AS effective_price
                            FROM app.retailer_product rp
                            JOIN app.retailer r
                              ON r.id = rp.retailer_id
                            JOIN LATERAL (
                                SELECT po.price_date,
                                       po.regular_price,
                                       po.discounted_price,
                                       po.discount_start,
                                       po.discount_end
                                FROM app.price_observation po
                                WHERE po.retailer_product_id = rp.id
                                ORDER BY po.price_date DESC,
                                         po.id DESC
                                LIMIT 1
                            ) latest_price ON TRUE
                            WHERE (
                                sli.barcode IS NOT NULL
                                AND rp.barcode = sli.barcode
                            )
                            OR (
                                sli.barcode IS NULL
                                AND rp.name ILIKE
                                    '%' || sli.name || '%'
                            )
                            ORDER BY effective_price ASC NULLS LAST,
                                     rp.id ASC
                            LIMIT 1
                        ) offer ON TRUE
                        WHERE sli.shopping_list_id = ?
                        ORDER BY sli.id ASC
                        """)
                .param(1, listId)
                .query(PRICE_ITEM_ROW_MAPPER)
                .list();
    }
}