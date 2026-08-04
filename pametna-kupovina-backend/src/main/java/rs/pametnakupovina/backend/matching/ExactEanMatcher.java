package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.Optional;

@Component
public class ExactEanMatcher {

    private static final String CANONICAL_KEY_PREFIX = "EAN:";

    private final JdbcClient jdbcClient;
    private final EanValidator eanValidator;

    public ExactEanMatcher(
            JdbcClient jdbcClient,
            EanValidator eanValidator
    ) {
        this.jdbcClient = jdbcClient;
        this.eanValidator = eanValidator;
    }

    public Optional<Long> matchOrCreate(
            String barcode,
            String productName,
            String normalizedProductName,
            String brand,
            BigDecimal quantityValue,
            String baseUnit
    ) {
        Optional<String> validEan = eanValidator.normalize(barcode);

        if (validEan.isEmpty()) {
            return Optional.empty();
        }

        String ean = validEan.get();

        Long canonicalProductId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product AS existing (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            barcode,
                            quantity_value,
                            base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (barcode)
                            WHERE barcode IS NOT NULL
                        DO UPDATE SET
                            normalized_name = COALESCE(
                                existing.normalized_name,
                                EXCLUDED.normalized_name
                            ),
                            brand = COALESCE(
                                existing.brand,
                                EXCLUDED.brand
                            ),
                            quantity_value = COALESCE(
                                existing.quantity_value,
                                EXCLUDED.quantity_value
                            ),
                            base_unit = COALESCE(
                                existing.base_unit,
                                EXCLUDED.base_unit
                            ),
                            updated_at = NOW()
                        RETURNING id
                        """)
                .param(1, CANONICAL_KEY_PREFIX + ean)
                .param(2, productName)
                .param(3, normalizedProductName)
                .param(4, brand, Types.VARCHAR)
                .param(5, ean)
                .param(6, quantityValue, Types.NUMERIC)
                .param(7, baseUnit, Types.VARCHAR)
                .query(Long.class)
                .single();

        return Optional.of(canonicalProductId);
    }
}
