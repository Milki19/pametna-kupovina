package rs.pametnakupovina.backend.loyalty;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class LoyaltyCardRepository {

    private final JdbcClient jdbcClient;

    public LoyaltyCardRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<LoyaltyCard> findAll(long accountId) {
        return jdbcClient.sql("""
                        SELECT id, name, card_number, barcode_format
                        FROM app.loyalty_card
                        WHERE account_id = :accountId
                        ORDER BY name, id
                        """)
                .param("accountId", accountId)
                .query(LoyaltyCardRepository::toCard)
                .list();
    }

    /** Ista kartica dodata dvaput samo osveži naziv i oblik koda. */
    public LoyaltyCard save(
            long accountId,
            String name,
            String cardNumber,
            String barcodeFormat
    ) {
        return jdbcClient.sql("""
                        INSERT INTO app.loyalty_card (
                            account_id, name, card_number, barcode_format
                        )
                        VALUES (:accountId, :name, :number, :format)
                        ON CONFLICT (account_id, card_number) DO UPDATE
                            SET name = EXCLUDED.name,
                                barcode_format = EXCLUDED.barcode_format,
                                updated_at = NOW()
                        RETURNING id, name, card_number, barcode_format
                        """)
                .param("accountId", accountId)
                .param("name", name)
                .param("number", cardNumber)
                .param("format", barcodeFormat)
                .query(LoyaltyCardRepository::toCard)
                .single();
    }

    public boolean delete(long accountId, long cardId) {
        return jdbcClient.sql("""
                        DELETE FROM app.loyalty_card
                        WHERE account_id = :accountId AND id = :cardId
                        """)
                .param("accountId", accountId)
                .param("cardId", cardId)
                .update() > 0;
    }

    public Optional<LoyaltyCard> findById(long accountId, long cardId) {
        return jdbcClient.sql("""
                        SELECT id, name, card_number, barcode_format
                        FROM app.loyalty_card
                        WHERE account_id = :accountId AND id = :cardId
                        """)
                .param("accountId", accountId)
                .param("cardId", cardId)
                .query(LoyaltyCardRepository::toCard)
                .optional();
    }

    private static LoyaltyCard toCard(
            java.sql.ResultSet resultSet,
            int rowNumber
    ) throws java.sql.SQLException {
        return new LoyaltyCard(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("card_number"),
                resultSet.getString("barcode_format")
        );
    }
}
