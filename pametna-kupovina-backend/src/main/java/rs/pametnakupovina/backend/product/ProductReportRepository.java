package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;

@Repository
public class ProductReportRepository {

    private final JdbcClient jdbcClient;

    public ProductReportRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean productExists(long canonicalProductId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM app.canonical_product WHERE id = ?)")
                .param(1, canonicalProductId)
                .query(Boolean.class)
                .single();
    }

    /** The listing is this product, under its own barcode or merged into it. */
    public boolean listingBelongsToProduct(long canonicalProductId, long retailerProductId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.retailer_product AS product
                            WHERE product.id = ?
                              AND (
                                  product.canonical_product_id = ?
                                  OR product.product_family_id IN (
                                      SELECT member.family_id
                                      FROM app.product_family_member AS member
                                      WHERE member.canonical_product_id = ?
                                  )
                              )
                        )
                        """)
                .param(1, retailerProductId)
                .param(2, canonicalProductId)
                .param(3, canonicalProductId)
                .query(Boolean.class)
                .single();
    }

    public long insert(
            long canonicalProductId,
            Long retailerProductId,
            ProductReportReason reason,
            String note,
            String clientTokenHash
    ) {
        return jdbcClient.sql("""
                        INSERT INTO app.product_report (
                            canonical_product_id,
                            retailer_product_id,
                            reason,
                            note,
                            client_token_hash
                        )
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, canonicalProductId)
                .param(2, retailerProductId, Types.BIGINT)
                .param(3, reason.name())
                .param(4, note, Types.VARCHAR)
                .param(5, clientTokenHash, Types.VARCHAR)
                .query(Long.class)
                .single();
    }
}
