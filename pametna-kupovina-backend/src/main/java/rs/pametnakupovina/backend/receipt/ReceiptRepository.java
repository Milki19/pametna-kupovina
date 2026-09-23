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

    /**
     * Stavke se upisuju jednom; drugo skeniranje istog računa ih ne duplira.
     */
    public void saveItems(long receiptId, List<Receipt.ReceiptItem> items) {
        if (items.isEmpty()) {
            return;
        }

        for (Receipt.ReceiptItem item : items) {
            jdbcClient.sql("""
                            INSERT INTO app.receipt_item (
                                receipt_id, line_number, name, quantity,
                                unit_of_measure, unit_price, total_price,
                                product_family_id
                            )
                            VALUES (
                                :receiptId, :line, :name, :quantity,
                                :unit, :unitPrice, :total, :familyId
                            )
                            ON CONFLICT (receipt_id, line_number) DO NOTHING
                            """)
                    .param("receiptId", receiptId)
                    .param("line", item.lineNumber())
                    .param("name", item.name())
                    .param("quantity", item.quantity())
                    .param("unit", item.unitOfMeasure())
                    .param("unitPrice", item.unitPrice())
                    .param("total", item.totalPrice())
                    .param("familyId", item.productFamilyId())
                    .update();
        }

        jdbcClient.sql("""
                        UPDATE app.receipt
                           SET items_read_at = NOW()
                         WHERE id = :receiptId
                        """)
                .param("receiptId", receiptId)
                .update();
    }

    /**
     * Ime iz QR koda je ono što je kasa upisala, a često ga i nema. Kad
     * Poreska uprava kaže svoje, ono ga zamenjuje — ali se prazno nikad ne
     * upisuje preko punog.
     */
    public void nameShop(
            long receiptId,
            String shopName,
            String taxIdentificationNumber
    ) {
        jdbcClient.sql("""
                        UPDATE app.receipt
                           SET shop_name = :shop,
                               tax_identification_number = COALESCE(
                                   :tin, tax_identification_number
                               )
                         WHERE id = :receiptId
                        """)
                .param("receiptId", receiptId)
                .param("shop", shopName)
                .param("tin", taxIdentificationNumber)
                .update();
    }

    public boolean itemsAlreadyRead(long receiptId) {
        return jdbcClient.sql("""
                        SELECT items_read_at IS NOT NULL
                        FROM app.receipt WHERE id = :receiptId
                        """)
                .param("receiptId", receiptId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
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
                               unit_price, total_price, product_family_id
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
                        resultSet.getBigDecimal("total_price"),
                        resultSet.getObject("product_family_id", Long.class)
                ))
                .list();
    }

    /**
     * Šta kupac zaista kupuje, po broju puta. Računa se po proizvodu, ne po
     * nazivu sa kase, jer isti jogurt u dva lanca ima dva imena.
     */
    public List<Habit> whatTheyBuy(long accountId, int limit) {
        return jdbcClient.sql("""
                        SELECT item.product_family_id,
                               COALESCE(
                                   family.composed_name, family.display_name
                               ) AS name,
                               COUNT(DISTINCT receipt.id) AS times,
                               MAX(receipt.issued_at) AS last_bought
                        FROM app.receipt_item AS item
                        JOIN app.receipt AS receipt
                          ON receipt.id = item.receipt_id
                        JOIN app.product_family AS family
                          ON family.id = item.product_family_id
                        WHERE receipt.account_id = :accountId
                          AND item.product_family_id IS NOT NULL
                        GROUP BY item.product_family_id,
                                 family.composed_name,
                                 family.display_name
                        ORDER BY times DESC, last_bought DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new Habit(
                        resultSet.getLong("product_family_id"),
                        resultSet.getString("name"),
                        resultSet.getInt("times"),
                        resultSet.getObject("last_bought", java.time.OffsetDateTime.class)
                                .toInstant()
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

    /**
     * Isti mesec, po nedeljama (1-7, 8-14, 15-21, 22-28, 29-kraj), kao na
     * mani.rs. Bucket se računa iz dana u mesecu, pa poslednja nedelja ima
     * 2-3 dana i to je normalno.
     */
    public List<WeeklySpending> spendingByWeek(long accountId, LocalDate monthStart) {
        return jdbcClient.sql("""
                        SELECT bucket, SUM(total_amount) AS spent
                        FROM (
                            SELECT total_amount,
                                   (EXTRACT(
                                       DAY FROM (issued_at AT TIME ZONE 'Europe/Belgrade')
                                   )::int - 1) / 7 AS bucket
                            FROM app.receipt
                            WHERE account_id = :accountId
                              AND (issued_at AT TIME ZONE 'Europe/Belgrade')::date
                                  >= :monthStart
                              AND (issued_at AT TIME ZONE 'Europe/Belgrade')::date
                                  < (:monthStart + INTERVAL '1 month')::date
                        ) AS bucketed
                        GROUP BY bucket
                        ORDER BY bucket
                        """)
                .param("accountId", accountId)
                .param("monthStart", monthStart)
                .query((resultSet, rowNumber) -> new WeeklySpending(
                        resultSet.getInt("bucket"),
                        resultSet.getBigDecimal("spent")
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

    /**
     * Izabrani mesec po glavnim kategorijama kataloga (Mleko ide pod Mlečne
     * proizvode). Stavka bez prepoznatog proizvoda i račun čije stavke još
     * nisu stigle od Poreske uprave idu u „Ostalo", da zbir ostane zbir meseca.
     */
    public List<CategorySpending> spendingByCategory(long accountId, LocalDate monthStart) {
        return jdbcClient.sql("""
                        WITH month_receipt AS (
                            SELECT id, total_amount
                            FROM app.receipt
                            WHERE account_id = :accountId
                              AND (issued_at AT TIME ZONE 'Europe/Belgrade')::date
                                  >= :monthStart
                              AND (issued_at AT TIME ZONE 'Europe/Belgrade')::date
                                  < (:monthStart + INTERVAL '1 month')::date
                        )
                        SELECT category, SUM(spent) AS spent
                        FROM (
                            SELECT COALESCE(parent.name, own.name, 'Ostalo') AS category,
                                   item.total_price AS spent
                            FROM month_receipt AS receipt
                            JOIN app.receipt_item AS item
                              ON item.receipt_id = receipt.id
                            LEFT JOIN app.product_family AS family
                              ON family.id = item.product_family_id
                            LEFT JOIN app.product_category AS own
                              ON own.id = family.product_category_id
                            LEFT JOIN app.product_category AS parent
                              ON parent.id = own.parent_id
                            UNION ALL
                            SELECT 'Ostalo', receipt.total_amount
                            FROM month_receipt AS receipt
                            WHERE NOT EXISTS (
                                SELECT 1 FROM app.receipt_item AS item
                                WHERE item.receipt_id = receipt.id
                            )
                        ) AS line
                        GROUP BY category
                        ORDER BY spent DESC, category
                        """)
                .param("accountId", accountId)
                .param("monthStart", monthStart)
                .query((resultSet, rowNumber) -> new CategorySpending(
                        resultSet.getString("category"),
                        resultSet.getBigDecimal("spent")
                ))
                .list();
    }

    /**
     * @param times koliko različitih računa sadrži taj proizvod; dve kutije
     *              na istom računu su i dalje jedna kupovina
     */
    public record Habit(
            long productFamilyId,
            String name,
            int times,
            Instant lastBought
    ) {
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

    public record WeeklySpending(
            int bucket,
            BigDecimal spent
    ) {
    }

    public record CategorySpending(
            String category,
            BigDecimal spent
    ) {
    }
}
