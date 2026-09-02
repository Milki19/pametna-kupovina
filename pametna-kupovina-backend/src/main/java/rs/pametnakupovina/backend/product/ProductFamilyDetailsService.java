package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class ProductFamilyDetailsService {

    private final JdbcClient jdbcClient;
    private final CanonicalProductSearchRepository searchRepository;

    public ProductFamilyDetailsService(
            JdbcClient jdbcClient,
            CanonicalProductSearchRepository searchRepository
    ) {
        this.jdbcClient = jdbcClient;
        this.searchRepository = searchRepository;
    }

    public Optional<ProductFamilyDetailsResponse> find(
            Long productFamilyId
    ) {
        if (productFamilyId == null || productFamilyId <= 0) {
            throw new IllegalArgumentException(
                    "productFamilyId mora biti pozitivan"
            );
        }

        Optional<FamilyHeader> header = jdbcClient.sql("""
                        SELECT family.id,
                               family.display_name,
                               brand.display_name AS brand,
                               category.code AS category_code,
                               category.name AS category_name,
                               family.quantity_value,
                               family.base_unit,
                               family.review_status
                        FROM app.product_family AS family
                        LEFT JOIN app.brand AS brand
                          ON brand.id = family.brand_id
                        LEFT JOIN app.product_category AS category
                          ON category.id = family.product_category_id
                        WHERE family.id = ?
                          AND family.review_status <> 'REJECTED'
                        """)
                .param(1, productFamilyId)
                .query((resultSet, rowNumber) -> new FamilyHeader(
                        resultSet.getLong("id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("brand"),
                        resultSet.getString("category_code"),
                        resultSet.getString("category_name"),
                        resultSet.getBigDecimal("quantity_value"),
                        resultSet.getString("base_unit"),
                        resultSet.getString("review_status")
                ))
                .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<ProductFamilyVariant> variants = jdbcClient.sql("""
                        SELECT canonical.id,
                               canonical.name,
                               COALESCE(
                                   brand.display_name,
                                   canonical.brand
                               ) AS brand,
                               canonical.barcode,
                               canonical.quantity_value,
                               canonical.base_unit,
                               member.confidence,
                               member.reviewed
                        FROM app.product_family_member AS member
                        JOIN app.canonical_product AS canonical
                          ON canonical.id = member.canonical_product_id
                        LEFT JOIN app.brand AS brand
                          ON brand.id = canonical.brand_id
                        WHERE member.family_id = ?
                        ORDER BY canonical.name, canonical.barcode,
                                 canonical.id
                        """)
                .param(1, productFamilyId)
                .query((resultSet, rowNumber) ->
                        new ProductFamilyVariant(
                                resultSet.getLong("id"),
                                resultSet.getString("name"),
                                resultSet.getString("brand"),
                                resultSet.getString("barcode"),
                                resultSet.getBigDecimal("quantity_value"),
                                resultSet.getString("base_unit"),
                                resultSet.getBigDecimal("confidence"),
                                resultSet.getBoolean("reviewed")
                        ))
                .list();

        List<ProductRetailerAvailability> availability = searchRepository
                .findAvailability(List.of(productFamilyId))
                .stream()
                .map(CanonicalProductSearchRepository
                        .ProductAvailabilityRow::availability)
                .toList();

        FamilyHeader value = header.orElseThrow();
        return Optional.of(new ProductFamilyDetailsResponse(
                value.id(),
                value.name(),
                value.brand(),
                value.categoryCode(),
                value.categoryName(),
                value.quantityValue(),
                value.baseUnit(),
                "REVIEW_REQUIRED".equals(value.reviewStatus()),
                variants,
                availability
        ));
    }

    private record FamilyHeader(
            Long id,
            String name,
            String brand,
            String categoryCode,
            String categoryName,
            BigDecimal quantityValue,
            String baseUnit,
            String reviewStatus
    ) {
    }
}
