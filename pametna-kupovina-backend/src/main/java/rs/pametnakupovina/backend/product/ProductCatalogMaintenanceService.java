package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductCatalogMaintenanceService {

    private final JdbcClient jdbcClient;

    public ProductCatalogMaintenanceService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public ProductCatalogRefreshResult refreshRetailer(long retailerId) {
        String retailerCode = jdbcClient.sql("""
                        SELECT code
                        FROM app.retailer
                        WHERE id = ?
                        """)
                .param(1, retailerId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Trgovac nije pronađen: " + retailerId
                ));

        synchronizeBrands(retailerId);
        synchronizeFamilies(retailerId);
        synchronizeProductCategories(retailerId);
        synchronizeFamilyCategories(retailerId);
        synchronizeProductTypes(retailerId);
        synchronizeFamilyProductTypes(retailerId);
        synchronizeProductAttributes(retailerId);
        synchronizeIdentityCandidates(retailerId);
        synchronizePresence(retailerId);

        return readResult(retailerId, retailerCode);
    }

    public List<ProductCatalogRefreshResult> refreshAll() {
        return jdbcClient.sql("""
                        SELECT id
                        FROM app.retailer
                        ORDER BY id
                        """)
                .query(Long.class)
                .list()
                .stream()
                .map(this::refreshRetailer)
                .toList();
    }

    private void synchronizeBrands(long retailerId) {
        jdbcClient.sql("""
                    INSERT INTO app.brand (normalized_name, display_name)
                    SELECT normalized_name, MIN(display_name)
                    FROM (
                        SELECT LOWER(REGEXP_REPLACE(
                                   BTRIM(product.brand),
                                   '[^[:alnum:]]+',
                                   ' ',
                                   'g'
                               )) AS normalized_name,
                               BTRIM(product.brand) AS display_name
                        FROM app.retailer_product AS product
                        WHERE product.retailer_id = ?
                          AND NULLIF(BTRIM(product.brand), '') IS NOT NULL
                        UNION ALL
                        SELECT LOWER(REGEXP_REPLACE(
                                   BTRIM(canonical.brand),
                                   '[^[:alnum:]]+',
                                   ' ',
                                   'g'
                               )),
                               BTRIM(canonical.brand)
                        FROM app.canonical_product AS canonical
                        JOIN app.retailer_product AS product
                          ON product.canonical_product_id = canonical.id
                        WHERE product.retailer_id = ?
                          AND NULLIF(BTRIM(canonical.brand), '') IS NOT NULL
                    ) AS source_brand
                    WHERE normalized_name <> ''
                    GROUP BY normalized_name
                    ON CONFLICT (normalized_name) DO NOTHING
                    """)
                .param(1, retailerId)
                .param(2, retailerId)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.brand (
                        normalized_name,
                        display_name,
                        private_label,
                        owner_retailer_id
                    )
                    SELECT private_brand.normalized_name,
                           private_brand.display_name,
                           TRUE,
                           retailer.id
                    FROM (
                        VALUES
                            ('premia', 'Premia', 'MAXI'),
                            ('k plus', 'K Plus', 'IDEA_RODA'),
                            ('pilos', 'Pilos', 'LIDL')
                    ) AS private_brand(
                        normalized_name,
                        display_name,
                        retailer_code
                    )
                    JOIN app.retailer AS retailer
                      ON retailer.code = private_brand.retailer_code
                    ON CONFLICT (normalized_name) DO UPDATE SET
                        display_name = EXCLUDED.display_name,
                        private_label = TRUE,
                        owner_retailer_id = EXCLUDED.owner_retailer_id,
                        updated_at = NOW()
                    """).update();

        jdbcClient.sql("""
                    INSERT INTO app.brand_alias (brand_id, normalized_alias)
                    SELECT id, normalized_name
                    FROM app.brand
                    ON CONFLICT (normalized_alias) DO NOTHING
                    """).update();

        jdbcClient.sql("""
                    INSERT INTO app.brand_alias (brand_id, normalized_alias)
                    SELECT brand.id, alias.normalized_alias
                    FROM (
                        VALUES
                            ('premia', 'premia'),
                            ('k plus', 'kplus'),
                            ('k plus', 'k plus'),
                            ('pilos', 'pilos')
                    ) AS alias(brand_name, normalized_alias)
                    JOIN app.brand AS brand
                      ON brand.normalized_name = alias.brand_name
                    ON CONFLICT (normalized_alias) DO NOTHING
                    """).update();

        jdbcClient.sql("""
                    UPDATE app.retailer_product AS product
                    SET brand_id = (
                        SELECT brand.id
                        FROM app.brand
                        WHERE brand.normalized_name = LOWER(REGEXP_REPLACE(
                                  BTRIM(product.brand),
                                  '[^[:alnum:]]+',
                                  ' ',
                                  'g'
                              ))
                           OR (
                                NULLIF(BTRIM(product.brand), '') IS NULL
                                AND (
                                    (brand.normalized_name = 'premia'
                                        AND product.normalized_name
                                            ~ '(^| )premia( |$)')
                                    OR (brand.normalized_name = 'k plus'
                                        AND product.normalized_name
                                            ~ '(^| )k ?plus( |$)')
                                    OR (brand.normalized_name = 'pilos'
                                        AND product.normalized_name
                                            ~ '(^| )pilos( |$)')
                                )
                           )
                        ORDER BY CASE
                                     WHEN NULLIF(BTRIM(product.brand), '')
                                             IS NOT NULL THEN 1
                                     ELSE 2
                                 END,
                                 brand.id
                        LIMIT 1
                    )
                    WHERE product.retailer_id = ?
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    UPDATE app.canonical_product AS canonical
                    SET brand_id = (
                        SELECT brand.id
                        FROM app.brand
                        WHERE brand.normalized_name = LOWER(REGEXP_REPLACE(
                                  BTRIM(canonical.brand),
                                  '[^[:alnum:]]+',
                                  ' ',
                                  'g'
                              ))
                           OR (
                                NULLIF(BTRIM(canonical.brand), '') IS NULL
                                AND (
                                    (brand.normalized_name = 'premia'
                                        AND canonical.normalized_name
                                            ~ '(^| )premia( |$)')
                                    OR (brand.normalized_name = 'k plus'
                                        AND canonical.normalized_name
                                            ~ '(^| )k ?plus( |$)')
                                    OR (brand.normalized_name = 'pilos'
                                        AND canonical.normalized_name
                                            ~ '(^| )pilos( |$)')
                                )
                           )
                        ORDER BY CASE
                                     WHEN NULLIF(BTRIM(canonical.brand), '')
                                             IS NOT NULL THEN 1
                                     ELSE 2
                                 END,
                                 brand.id
                        LIMIT 1
                    )
                    WHERE EXISTS (
                        SELECT 1
                        FROM app.retailer_product AS product
                        WHERE product.retailer_id = ?
                          AND product.canonical_product_id = canonical.id
                    )
                    """)
                .param(1, retailerId)
                .update();
    }

    private void synchronizeFamilies(long retailerId) {
        jdbcClient.sql("""
                    INSERT INTO app.product_family (
                        family_key,
                        display_name,
                        normalized_name,
                        brand_id,
                        quantity_value,
                        base_unit,
                        review_status
                    )
                    SELECT 'SEM:' || MD5(signature),
                           MIN(name),
                           normalized_name,
                           brand_id,
                           quantity_value,
                           base_unit,
                           CASE
                               WHEN COUNT(DISTINCT barcode)
                                   FILTER (WHERE barcode IS NOT NULL) > 1
                                   THEN 'REVIEW_REQUIRED'
                               ELSE 'ACTIVE'
                           END
                    FROM (
                        SELECT canonical.name,
                               canonical.normalized_name,
                               canonical.brand_id,
                               canonical.quantity_value,
                               canonical.base_unit,
                               canonical.barcode,
                               canonical.normalized_name || '|' ||
                                   COALESCE(brand.normalized_name, '') || '|' ||
                                   COALESCE(
                                       canonical.quantity_value::TEXT,
                                       ''
                                   ) || '|' ||
                                   COALESCE(canonical.base_unit, '')
                                       AS signature
                        FROM app.canonical_product AS canonical
                        JOIN app.retailer_product AS product
                          ON product.canonical_product_id = canonical.id
                        LEFT JOIN app.brand AS brand
                          ON brand.id = canonical.brand_id
                        WHERE product.retailer_id = ?
                    ) AS signature_rows
                    GROUP BY signature,
                             normalized_name,
                             brand_id,
                             quantity_value,
                             base_unit
                    ON CONFLICT (family_key) DO UPDATE SET
                        display_name = EXCLUDED.display_name,
                        brand_id = EXCLUDED.brand_id,
                        quantity_value = EXCLUDED.quantity_value,
                        base_unit = EXCLUDED.base_unit,
                        updated_at = NOW()
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.product_family_member (
                        family_id,
                        canonical_product_id,
                        relation_type,
                        confidence
                    )
                    SELECT family.id,
                           canonical.id,
                           'SINGLE_GTIN',
                           1.0000
                    FROM app.canonical_product AS canonical
                    JOIN app.retailer_product AS product
                      ON product.canonical_product_id = canonical.id
                    LEFT JOIN app.brand AS brand
                      ON brand.id = canonical.brand_id
                    JOIN app.product_family AS family
                      ON family.family_key = 'SEM:' || MD5(
                          canonical.normalized_name || '|' ||
                          COALESCE(brand.normalized_name, '') || '|' ||
                          COALESCE(canonical.quantity_value::TEXT, '') || '|' ||
                          COALESCE(canonical.base_unit, '')
                      )
                    WHERE product.retailer_id = ?
                    ON CONFLICT (canonical_product_id) DO NOTHING
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.product_family (
                        family_key,
                        display_name,
                        normalized_name,
                        brand_id,
                        quantity_value,
                        base_unit,
                        review_status
                    )
                    SELECT 'SEM:' || MD5(signature),
                           MIN(name),
                           normalized_name,
                           brand_id,
                           quantity_value,
                           base_unit,
                           'ACTIVE'
                    FROM (
                        SELECT product.name,
                               product.normalized_name,
                               product.brand_id,
                               product.quantity_value,
                               product.base_unit,
                               product.normalized_name || '|' ||
                                   COALESCE(brand.normalized_name, '') || '|' ||
                                   COALESCE(
                                       product.quantity_value::TEXT,
                                       ''
                                   ) || '|' ||
                                   COALESCE(product.base_unit, '')
                                       AS signature
                        FROM app.retailer_product AS product
                        LEFT JOIN app.brand AS brand
                          ON brand.id = product.brand_id
                        WHERE product.retailer_id = ?
                          AND product.canonical_product_id IS NULL
                    ) AS signature_rows
                    WHERE NULLIF(BTRIM(normalized_name), '') IS NOT NULL
                    GROUP BY signature,
                             normalized_name,
                             brand_id,
                             quantity_value,
                             base_unit
                    ON CONFLICT (family_key) DO UPDATE SET
                        display_name = EXCLUDED.display_name,
                        updated_at = NOW()
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    UPDATE app.retailer_product AS product
                    SET product_family_id = family.id
                    FROM app.product_family AS family
                    WHERE product.retailer_id = ?
                      AND family.family_key = 'SEM:' || MD5(
                          product.normalized_name || '|' ||
                          COALESCE((
                              SELECT brand.normalized_name
                              FROM app.brand AS brand
                              WHERE brand.id = product.brand_id
                          ), '') || '|' ||
                          COALESCE(product.quantity_value::TEXT, '') || '|' ||
                          COALESCE(product.base_unit, '')
                      )
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    UPDATE app.retailer_product AS product
                    SET product_family_id = member.family_id
                    FROM app.product_family_member AS member
                    WHERE product.retailer_id = ?
                      AND member.canonical_product_id =
                          product.canonical_product_id
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    WITH variant_counts AS (
                        SELECT member.family_id,
                               COUNT(DISTINCT canonical.barcode)
                                   FILTER (
                                       WHERE canonical.barcode IS NOT NULL
                                   ) AS barcode_count
                        FROM app.product_family_member AS member
                        JOIN app.canonical_product AS canonical
                          ON canonical.id = member.canonical_product_id
                        JOIN app.retailer_product AS product
                          ON product.canonical_product_id = canonical.id
                        WHERE product.retailer_id = ?
                        GROUP BY member.family_id
                    )
                    UPDATE app.product_family AS family
                    SET review_status = CASE
                            WHEN counts.barcode_count > 1
                                THEN 'REVIEW_REQUIRED'
                            ELSE family.review_status
                        END,
                        updated_at = NOW()
                    FROM variant_counts AS counts
                    WHERE counts.family_id = family.id
                    """)
                .param(1, retailerId)
                .update();
    }

    private void synchronizeProductCategories(long retailerId) {
        jdbcClient.sql("""
                    INSERT INTO app.retailer_product_category AS assignment (
                        retailer_product_id,
                        product_category_id,
                        confidence,
                        assignment_source
                    )
                    SELECT product.id,
                           matched.product_category_id,
                           matched.confidence,
                           matched.assignment_source
                    FROM app.retailer_product AS product
                    JOIN LATERAL (
                        SELECT candidate.product_category_id,
                               candidate.confidence,
                               candidate.assignment_source
                        FROM (
                            SELECT mapping.product_category_id,
                                   mapping.confidence,
                                   'SOURCE_CATEGORY_CODE'
                                       AS assignment_source,
                                   0 AS priority,
                                   0 AS pattern_length,
                                   mapping.id
                            FROM app.product_category_source_mapping AS mapping
                            WHERE UPPER(BTRIM(product.category_code)) =
                                  mapping.source_category_code
                              AND (
                                  mapping.retailer_id IS NULL
                                  OR mapping.retailer_id = product.retailer_id
                              )
                            UNION ALL
                            SELECT rule.product_category_id,
                                   rule.confidence,
                                   'NAME_PATTERN_RULE',
                                   rule.priority::INTEGER,
                                   LENGTH(rule.name_pattern),
                                   rule.id
                            FROM app.product_category_rule AS rule
                            WHERE rule.active = TRUE
                              AND (
                                  rule.retailer_id IS NULL
                                  OR rule.retailer_id = product.retailer_id
                              )
                              AND product.normalized_name ~ rule.name_pattern
                            UNION ALL
                            SELECT alias.product_category_id,
                                   CASE
                                       WHEN product.normalized_name =
                                            alias.normalized_alias
                                           THEN 0.9500
                                       ELSE 0.8500
                                   END,
                                   'NORMALIZED_NAME_PREFIX',
                                   2000,
                                   LENGTH(alias.normalized_alias),
                                   alias.id
                            FROM app.product_category_alias AS alias
                            WHERE product.normalized_name =
                                      alias.normalized_alias
                               OR product.normalized_name LIKE
                                      alias.normalized_alias || ' %'
                        ) AS candidate
                        ORDER BY candidate.priority,
                                 candidate.confidence DESC,
                                 candidate.pattern_length DESC,
                                 candidate.id
                        LIMIT 1
                    ) AS matched ON TRUE
                    WHERE product.retailer_id = ?
                    ON CONFLICT (retailer_product_id) DO UPDATE SET
                        product_category_id = EXCLUDED.product_category_id,
                        confidence = EXCLUDED.confidence,
                        assignment_source = EXCLUDED.assignment_source,
                        updated_at = NOW()
                    WHERE assignment.reviewed = FALSE
                    """)
                .param(1, retailerId)
                .update();
    }

    private void synchronizeFamilyCategories(long retailerId) {
        jdbcClient.sql("""
                    WITH affected_family AS (
                        SELECT DISTINCT product_family_id
                        FROM app.retailer_product
                        WHERE retailer_id = ?
                          AND product_family_id IS NOT NULL
                    ), category_counts AS (
                        SELECT product.product_family_id,
                               assignment.product_category_id,
                               COUNT(*) AS assignment_count,
                               MAX(assignment.confidence)
                                   AS maximum_confidence
                        FROM app.retailer_product AS product
                        JOIN affected_family AS affected
                          ON affected.product_family_id =
                              product.product_family_id
                        JOIN app.retailer_product_category AS assignment
                          ON assignment.retailer_product_id = product.id
                        GROUP BY product.product_family_id,
                                 assignment.product_category_id
                    ), category_choice AS (
                        SELECT DISTINCT ON (product_family_id)
                               product_family_id,
                               product_category_id
                        FROM category_counts
                        ORDER BY product_family_id,
                                 assignment_count DESC,
                                 maximum_confidence DESC,
                                 product_category_id
                    )
                    UPDATE app.product_family AS family
                    SET product_category_id = choice.product_category_id,
                        updated_at = NOW()
                    FROM category_choice AS choice
                    WHERE choice.product_family_id = family.id
                    """)
                .param(1, retailerId)
                .update();
    }

    private void synchronizeProductTypes(long retailerId) {
        jdbcClient.sql("""
                    DELETE FROM app.retailer_product_type AS assignment
                    USING app.retailer_product AS product
                    WHERE assignment.retailer_product_id = product.id
                      AND product.retailer_id = ?
                      AND assignment.reviewed = FALSE
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    DELETE FROM app.product_type_candidate AS candidate
                    USING app.retailer_product AS product
                    WHERE candidate.retailer_product_id = product.id
                      AND product.retailer_id = ?
                      AND candidate.status = 'PENDING'
                      AND candidate.algorithm_version = 'taxonomy-v2'
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.product_type_candidate (
                        retailer_product_id,
                        product_type_id,
                        confidence,
                        prediction_source,
                        evidence,
                        algorithm_version
                    )
                    SELECT prediction.retailer_product_id,
                           prediction.product_type_id,
                           prediction.confidence,
                           prediction.prediction_source,
                           prediction.evidence,
                           prediction.algorithm_version
                    FROM app.product_type_prediction AS prediction
                    JOIN app.retailer_product AS product
                      ON product.id = prediction.retailer_product_id
                    WHERE product.retailer_id = ?
                      AND prediction.confidence >= 0.7500
                      AND prediction.confidence < 0.9500
                      AND NOT EXISTS (
                          SELECT 1
                          FROM app.retailer_product_type AS assignment
                          WHERE assignment.retailer_product_id = product.id
                            AND assignment.reviewed = TRUE
                      )
                    ON CONFLICT (
                        retailer_product_id,
                        product_type_id,
                        algorithm_version
                    ) DO UPDATE SET
                        confidence = EXCLUDED.confidence,
                        prediction_source = EXCLUDED.prediction_source,
                        evidence = EXCLUDED.evidence,
                        updated_at = NOW()
                    WHERE product_type_candidate.status = 'PENDING'
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.retailer_product_type (
                        retailer_product_id,
                        product_type_id,
                        confidence,
                        assignment_source,
                        evidence,
                        algorithm_version
                    )
                    SELECT prediction.retailer_product_id,
                           prediction.product_type_id,
                           prediction.confidence,
                           prediction.prediction_source,
                           prediction.evidence,
                           prediction.algorithm_version
                    FROM app.product_type_prediction AS prediction
                    JOIN app.retailer_product AS product
                      ON product.id = prediction.retailer_product_id
                    WHERE product.retailer_id = ?
                      AND prediction.confidence >= 0.9500
                    ON CONFLICT (retailer_product_id) DO NOTHING
                    """)
                .param(1, retailerId)
                .update();
    }

    private void synchronizeProductAttributes(long retailerId) {
        jdbcClient.sql("""
                    DELETE FROM app.retailer_product_attribute AS assignment
                    USING app.retailer_product AS product
                    WHERE assignment.retailer_product_id = product.id
                      AND product.retailer_id = ?
                      AND assignment.reviewed = FALSE
                      AND assignment.assignment_source = 'NAME_EXTRACTION'
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.retailer_product_attribute (
                        retailer_product_id,
                        attribute_definition_id,
                        numeric_value,
                        unit,
                        confidence,
                        assignment_source,
                        evidence
                    )
                    SELECT product.id,
                           definition.id,
                           parsed.value,
                           'percent',
                           0.9700,
                           'NAME_EXTRACTION',
                           'PATTERN:FAT_PERCENT'
                    FROM app.retailer_product AS product
                    JOIN app.retailer_product_type AS type_assignment
                      ON type_assignment.retailer_product_id = product.id
                    JOIN app.product_type AS type
                      ON type.id = type_assignment.product_type_id
                     AND type.code IN (
                         'MILK', 'SOUR_MILK', 'FLAVORED_MILK',
                         'YOGURT', 'CHEESE', 'BUTTER', 'CREAM'
                     )
                    JOIN app.product_attribute_definition AS definition
                      ON definition.code = 'FAT_PERCENT'
                    CROSS JOIN LATERAL (
                        SELECT REPLACE(match[1], ',', '.')::NUMERIC
                                   AS value
                        FROM REGEXP_MATCH(
                            product.normalized_name,
                            '([0-9]+([.,][0-9]+)?) ?%'
                        ) AS match
                    ) AS parsed
                    WHERE product.retailer_id = ?
                      AND parsed.value > 0
                      AND parsed.value <= 100
                    ON CONFLICT (
                        retailer_product_id,
                        attribute_definition_id
                    ) DO NOTHING
                    """)
                .param(1, retailerId)
                .update();

        synchronizeTextAttribute(
                retailerId,
                "MILK_SOURCE",
                """
                CASE
                    WHEN product.normalized_name ~ '(^| )kozj[a-z]*( |$)'
                        THEN 'GOAT'
                    WHEN product.normalized_name ~ '(^| )ovcij[a-z]*( |$)'
                        THEN 'SHEEP'
                    WHEN product.normalized_name ~ '(^| )kravlj[a-z]*( |$)'
                        THEN 'COW'
                    WHEN product.normalized_name
                        ~ '(^| )(badem|soj[a-z]*|ovas|ovsen[a-z]*|kokos)( |$)'
                        THEN 'PLANT'
                    ELSE NULL
                END
                """,
                "PATTERN:MILK_SOURCE",
                List.of(
                        "MILK",
                        "SOUR_MILK",
                        "FLAVORED_MILK",
                        "POWDERED_MILK",
                        "PLANT_DRINK",
                        "YOGURT",
                        "CHEESE",
                        "BUTTER",
                        "CREAM"
                )
        );

        synchronizeTextAttribute(
                retailerId,
                "PROCESSING",
                """
                CASE
                    WHEN product.normalized_name
                        ~ '(^| )(uht|sterilizovan[a-z]*)( |$)'
                        THEN 'UHT'
                    WHEN product.normalized_name
                        ~ '(^| )pasterizovan[a-z]*( |$)'
                        THEN 'PASTEURIZED'
                    WHEN product.normalized_name
                        ~ '(^| )fermentisan[a-z]*( |$)'
                        THEN 'FERMENTED'
                    ELSE NULL
                END
                """,
                "PATTERN:PROCESSING",
                List.of(
                        "MILK",
                        "SOUR_MILK",
                        "FLAVORED_MILK",
                        "POWDERED_MILK",
                        "PLANT_DRINK",
                        "YOGURT",
                        "CHEESE",
                        "BUTTER",
                        "CREAM",
                        "JUICE"
                )
        );

        synchronizeTextAttribute(
                retailerId,
                "PACKAGING_FORM",
                """
                CASE
                    WHEN product.normalized_name ~ '(^| )pet( |$)'
                        THEN 'PET'
                    WHEN product.normalized_name
                        ~ '(^| )(tetra ?pak|tetrapak)( |$)'
                        THEN 'CARTON'
                    WHEN product.normalized_name
                        ~ '(^| )(limenka|can)( |$)'
                        THEN 'CAN'
                    WHEN product.normalized_name
                        ~ '(^| )(staklo|staklena)( |$)'
                        THEN 'GLASS'
                    ELSE NULL
                END
                """,
                "PATTERN:PACKAGING_FORM",
                null
        );
    }

    private void synchronizeTextAttribute(
            long retailerId,
            String attributeCode,
            String valueExpression,
            String evidence,
            List<String> applicableTypeCodes
    ) {
        String typeFilter = applicableTypeCodes == null
                ? ""
                : """
                  AND EXISTS (
                      SELECT 1
                      FROM app.retailer_product_type AS type_assignment
                      JOIN app.product_type AS product_type
                        ON product_type.id = type_assignment.product_type_id
                      WHERE type_assignment.retailer_product_id = product.id
                        AND product_type.code IN (:applicableTypeCodes)
                  )
                  """;

        JdbcClient.StatementSpec statement = jdbcClient.sql("""
                    INSERT INTO app.retailer_product_attribute (
                        retailer_product_id,
                        attribute_definition_id,
                        text_value,
                        confidence,
                        assignment_source,
                        evidence
                    )
                    SELECT product.id,
                           definition.id,
                           extracted.value,
                           0.9500,
                           'NAME_EXTRACTION',
                           :evidence
                    FROM app.retailer_product AS product
                    JOIN app.product_attribute_definition AS definition
                      ON definition.code = :attributeCode
                    CROSS JOIN LATERAL (
                        SELECT %s AS value
                    ) AS extracted
                    WHERE product.retailer_id = :retailerId
                      AND extracted.value IS NOT NULL
                    %s
                    ON CONFLICT (
                        retailer_product_id,
                        attribute_definition_id
                    ) DO NOTHING
                    """.formatted(valueExpression, typeFilter))
                .param("evidence", evidence)
                .param("attributeCode", attributeCode)
                .param("retailerId", retailerId);

        if (applicableTypeCodes != null) {
            statement = statement.param(
                    "applicableTypeCodes",
                    applicableTypeCodes
            );
        }

        statement.update();
    }

    private void synchronizeFamilyProductTypes(long retailerId) {
        jdbcClient.sql("""
                    WITH affected_family AS (
                        SELECT DISTINCT product_family_id
                        FROM app.retailer_product
                        WHERE retailer_id = ?
                          AND product_family_id IS NOT NULL
                    ), type_counts AS (
                        SELECT product.product_family_id,
                               assignment.product_type_id,
                               COUNT(*) AS assignment_count,
                               MAX(assignment.confidence)
                                   AS maximum_confidence
                        FROM app.retailer_product AS product
                        JOIN affected_family AS affected
                          ON affected.product_family_id =
                              product.product_family_id
                        JOIN app.retailer_product_type AS assignment
                          ON assignment.retailer_product_id = product.id
                        GROUP BY product.product_family_id,
                                 assignment.product_type_id
                    ), type_choice AS (
                        SELECT DISTINCT ON (product_family_id)
                               product_family_id,
                               product_type_id
                        FROM type_counts
                        ORDER BY product_family_id,
                                 assignment_count DESC,
                                 maximum_confidence DESC,
                                 product_type_id
                    )
                    UPDATE app.product_family AS family
                    SET product_type_id = choice.product_type_id,
                        updated_at = NOW()
                    FROM affected_family AS affected
                    LEFT JOIN type_choice AS choice
                      ON choice.product_family_id =
                         affected.product_family_id
                    WHERE family.id = affected.product_family_id
                      AND family.product_type_id IS DISTINCT FROM
                          choice.product_type_id
                    """)
                .param(1, retailerId)
                .update();
    }

    private void synchronizeIdentityCandidates(long retailerId) {
        jdbcClient.sql("""
                    INSERT INTO app.product_identity_candidate (
                        left_canonical_product_id,
                        right_canonical_product_id,
                        suggested_family_id,
                        score,
                        reason
                    )
                    SELECT left_member.canonical_product_id,
                           right_member.canonical_product_id,
                           left_member.family_id,
                           0.9200,
                           'Isti normalizovan naziv, brend, količina i '
                               || 'jedinica; različit barkod.'
                    FROM app.product_family_member AS left_member
                    JOIN app.product_family_member AS right_member
                      ON right_member.family_id = left_member.family_id
                     AND right_member.canonical_product_id >
                         left_member.canonical_product_id
                    JOIN app.canonical_product AS left_product
                      ON left_product.id = left_member.canonical_product_id
                    JOIN app.canonical_product AS right_product
                      ON right_product.id = right_member.canonical_product_id
                    WHERE left_product.barcode IS NOT NULL
                      AND right_product.barcode IS NOT NULL
                      AND left_product.barcode <> right_product.barcode
                      AND EXISTS (
                          SELECT 1
                          FROM app.retailer_product AS product
                          WHERE product.retailer_id = ?
                            AND product.product_family_id =
                                left_member.family_id
                      )
                    ON CONFLICT (
                        left_canonical_product_id,
                        right_canonical_product_id
                    ) DO UPDATE SET
                        suggested_family_id = EXCLUDED.suggested_family_id,
                        score = EXCLUDED.score,
                        reason = EXCLUDED.reason,
                        updated_at = NOW()
                    """)
                .param(1, retailerId)
                .update();
    }

    private void synchronizePresence(long retailerId) {
        jdbcClient.sql("""
                    DELETE FROM app.product_retailer_presence
                    WHERE retailer_id = ?
                    """)
                .param(1, retailerId)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.product_retailer_presence (
                        product_family_id,
                        retailer_id,
                        first_seen_date,
                        last_seen_date,
                        latest_price_date,
                        current_offer_count,
                        store_count,
                        format_count,
                        minimum_effective_price
                    )
                    SELECT product.product_family_id,
                           product.retailer_id,
                           MIN(offer.first_seen_date),
                           MAX(offer.last_seen_date),
                           MAX(offer.price_date),
                           COUNT(*)::INTEGER,
                           COUNT(DISTINCT offer.store_id)
                               FILTER (WHERE offer.store_id IS NOT NULL)
                               ::INTEGER,
                           COUNT(DISTINCT LOWER(BTRIM(
                               offer.retailer_format_name
                           ))) FILTER (
                               WHERE NULLIF(BTRIM(
                                   offer.retailer_format_name
                               ), '') IS NOT NULL
                           )::INTEGER,
                           MIN(
                               CASE
                                   WHEN offer.discounted_price > 0
                                       THEN offer.discounted_price
                                   WHEN offer.regular_price > 0
                                       THEN offer.regular_price
                               END
                           )
                    FROM app.current_price_offer AS offer
                    JOIN app.retailer_product AS product
                      ON product.id = offer.retailer_product_id
                    WHERE product.retailer_id = ?
                      AND product.product_family_id IS NOT NULL
                    GROUP BY product.product_family_id,
                             product.retailer_id
                    """)
                .param(1, retailerId)
                .update();
    }

    private ProductCatalogRefreshResult readResult(
            long retailerId,
            String retailerCode
    ) {
        return jdbcClient.sql("""
                        SELECT COUNT(DISTINCT product.product_family_id)
                                   FILTER (
                                       WHERE product.product_family_id
                                           IS NOT NULL
                                   )::INTEGER AS family_count,
                               COUNT(DISTINCT product.id)::INTEGER
                                   AS retailer_product_count,
                               COUNT(DISTINCT category.retailer_product_id)
                                   ::INTEGER AS categorized_product_count,
                               COUNT(DISTINCT presence.product_family_id)
                                   ::INTEGER AS presence_count,
                               COUNT(DISTINCT candidate.id)
                                   FILTER (
                                       WHERE candidate.status = 'PENDING'
                                   )::INTEGER
                                   AS pending_candidate_count
                        FROM app.retailer_product AS product
                        LEFT JOIN app.retailer_product_category AS category
                          ON category.retailer_product_id = product.id
                        LEFT JOIN app.product_retailer_presence AS presence
                          ON presence.product_family_id =
                              product.product_family_id
                         AND presence.retailer_id = product.retailer_id
                        LEFT JOIN app.product_identity_candidate AS candidate
                          ON candidate.suggested_family_id =
                              product.product_family_id
                        WHERE product.retailer_id = ?
                        """)
                .param(1, retailerId)
                .query((resultSet, rowNumber) ->
                        new ProductCatalogRefreshResult(
                                retailerId,
                                retailerCode,
                                resultSet.getInt("family_count"),
                                resultSet.getInt("retailer_product_count"),
                                resultSet.getInt("categorized_product_count"),
                                resultSet.getInt("presence_count"),
                                resultSet.getInt("pending_candidate_count")
                        ))
                .single();
    }
}
