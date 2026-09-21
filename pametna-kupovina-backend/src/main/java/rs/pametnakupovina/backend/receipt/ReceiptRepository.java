package rs.pametnakupovina.backend.receipt;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ReceiptRepository {

    private final JdbcClient jdbcClient;

    public ReceiptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Isti račun skeniran dvaput ostaje jedan. Vraća se šta god da se desilo,
     * jer kupcu je svejedno da li je njegov račun upravo zaveden ili je već
     * bio — bitno mu je da je unutra.
     */
    public long save(
            long accountId,
            FiscalReceiptStamp stamp,
            String verificationUrl,
            String shopName
    ) {
        jdbcClient.sql("""
                        INSERT INTO app.receipt (
                            account_id, verification_key, shop_name,
                            issued_at, total_amount, verification_url
                        )
                        VALUES (
                            :accountId, :key, :shop,
                            :issuedAt, :total, :url
                        )
                        ON CONFLICT (account_id, verification_key)
                        DO NOTHING
                        """)
                .param("accountId", accountId)
                .param("key", stamp.invoiceNumber())
                .param("shop", shopName)
                .param("issuedAt", stamp.issuedAt().atOffset(java.time.ZoneOffset.UTC))
                .param("total", stamp.totalAmount())
                .param("url", verificationUrl)
                .update();

        return jdbcClient.sql("""
                        SELECT id FROM app.receipt
                        WHERE account_id = :accountId
                          AND verification_key = :key
                        """)
                .param("accountId", accountId)
                .param("key", stamp.invoiceNumber())
                .query(Long.class)
                .single();
    }

    public List<Receipt> findAll(long accountId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, verification_key, shop_name, issued_at,
                               total_amount, items_read_at
                        FROM app.receipt
                        WHERE account_id = :accountId
                        ORDER BY issued_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new Receipt(
                        resultSet.getLong("id"),
                        resultSet.getString("verification_key"),
                        resultSet.getString("shop_name"),
                        resultSet.getObject("issued_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        resultSet.getBigDecimal("total_amount"),
                        resultSet.getObject("items_read_at") != null,
                        List.of()
                ))
                .list();
    }

    public Optional<Receipt> findById(long accountId, long receiptId) {
        Optional<Receipt> receipt = jdbcClient.sql("""
                        SELECT id, verification_key, shop_name, issued_at,
                               total_amount, items_read_at
                        FROM app.receipt
                        WHERE account_id = :accountId AND id = :receiptId
                        """)
                .param("accountId", accountId)
                .param("receiptId", receiptId)
                .query((resultSet, rowNumber) -> new Receipt(
                        resultSet.getLong("id"),
                        resultSet.getString("verification_key"),
                        resultSet.getString("shop_name"),
                        resultSet.getObject("issued_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        resultSet.getBigDecimal("total_amount"),
                        resultSet.getObject("items_read_at") != null,
                        List.of()
                ))
                .optional();

        return receipt.map(found -> new Receipt(
                found.id(),
                found.invoiceNumber(),
                found.shopName(),
                found.issuedAt(),
                found.totalAmount(),
                found.itemsRead(),
                itemsOf(found.id())
        ));
    }

    private List<Receipt.ReceiptItem> itemsOf(long receiptId) {
        return jdbcClient.sql("""
                        SELECT line_number, name, quantity, unit_of_measure,
                               unit_price, total_price
                        FROM app.receipt_item
                        WHERE receipt_id = :receiptId
                        ORDER BY line_number
                        """)
                .param("receiptId", receiptId)
                .query((resultSet, rowNumber) -> new Receipt.ReceiptItem(
                        resultSet.getInt("line_number"),
                        resultSet.getString("name"),
                        resultSet.getBigDecimal("quantity"),
                        resultSet.getString("unit_of_measure"),
                        resultSet.getBigDecimal("unit_price"),
                        resultSet.getBigDecimal("total_price")
                ))
                .list();
    }

    /** Koliko je otišlo po mesecu, najskorije prvo. */
    public List<MonthlySpending> spendingByMonth(long accountId, int months) {
        return jdbcClient.sql("""
                        SELECT DATE_TRUNC(
                                   'month',
                                   issued_at AT TIME ZONE 'Europe/Belgrade'
                               )::date AS month,
                               SUM(total_amount) AS spent,
                               COUNT(*) AS receipts
                        FROM app.receipt
                        WHERE account_id = :accountId
                        GROUP BY month
                        ORDER BY month DESC
                        LIMIT :months
                        """)
                .param("accountId", accountId)
                .param("months", months)
                .query((resultSet, rowNumber) -> new MonthlySpending(
                        resultSet.getObject("month", LocalDate.class),
                        resultSet.getBigDecimal("spent"),
                        resultSet.getInt("receipts")
                ))
                .list();
    }

    /** Gde je otišlo, od najviše ka najmanje. */
    public List<ShopSpending> spendingByShop(long accountId, int shops) {
        return jdbcClient.sql("""
                        SELECT shop_name,
                               SUM(total_amount) AS spent,
                               COUNT(*) AS receipts,
                               MAX(issued_at) AS last_visit
                        FROM app.receipt
                        WHERE account_id = :accountId
                        GROUP BY shop_name
                        ORDER BY spent DESC, shop_name
                        LIMIT :shops
                        """)
                .param("accountId", accountId)
                .param("shops", shops)
                .query((resultSet, rowNumber) -> new ShopSpending(
                        resultSet.getString("shop_name"),
                        resultSet.getBigDecimal("spent"),
                        resultSet.getInt("receipts"),
                        resultSet.getObject("last_visit", java.time.OffsetDateTime.class)
                                .toInstant()
                ))
                .list();
    }

    public record MonthlySpending(
            LocalDate month,
            BigDecimal spent,
            int receipts
    ) {
    }

    public record ShopSpending(
            String shopName,
            BigDecimal spent,
            int receipts,
            Instant lastVisit
    ) {
    }
}
