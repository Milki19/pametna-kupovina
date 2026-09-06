package rs.pametnakupovina.backend.retailerlocation;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.priceimport.RetailerDataSourceRepository;
import rs.pametnakupovina.backend.retailer.Retailer;
import rs.pametnakupovina.backend.retailer.RetailerRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RetailerLocationImportService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    RetailerLocationImportService.class
            );

    private static final CSVFormat CSV_FORMAT =
            CSVFormat.DEFAULT.builder()
                    .setDelimiter(';')
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true)
                    .setIgnoreHeaderCase(true)
                    .setIgnoreSurroundingSpaces(true)
                    .setTrim(true)
                    .get();

    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "external_code",
            "name",
            "address",
            "city",
            "active"
    );

    private static final String DEFAULT_FORMAT_CODE = "STANDARD";
    private static final String DEFAULT_FORMAT_NAME = "Standardni format";

    private final JdbcClient jdbcClient;
    private final RetailerRepository retailerRepository;
    private final RetailerDataSourceRepository dataSourceRepository;

    public RetailerLocationImportService(
            JdbcClient jdbcClient,
            RetailerRepository retailerRepository,
            RetailerDataSourceRepository dataSourceRepository
    ) {
        this.jdbcClient = jdbcClient;
        this.retailerRepository = retailerRepository;
        this.dataSourceRepository = dataSourceRepository;
    }

    public RetailerLocationImportResult importLocations(
            String retailerCode,
            InputStream inputStream,
            int maxRows
    ) {
        Retailer retailer =
                retailerRepository.findByCode(retailerCode)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Prodavnica nije pronađena: "
                                                + retailerCode
                                )
                        );

        int rowsRead = 0;
        int rowsSaved = 0;
        Long dataSourceId = ensureLocationDataSourceId(retailer.id());

        if (!dataSourceRepository.tryMarkRunning(dataSourceId)) {
            throw new IllegalStateException(
                    "Import lokacija za ovaj izvor je već u toku."
            );
        }

        try (
                Reader reader = createBomAwareReader(inputStream);
                CSVParser parser = CSV_FORMAT.parse(reader)
        ) {
            ImportColumns importColumns = validateHeaders(parser);

            Long defaultStoreFormatId =
                    importColumns.hasStoreFormatColumns()
                            ? null
                            : ensureStoreFormatId(
                                    retailer.id(),
                                    DEFAULT_FORMAT_CODE,
                                    DEFAULT_FORMAT_NAME
                            );
            for (CSVRecord record : parser) {
                if (rowsRead >= maxRows) {
                    break;
                }

                rowsRead++;

                try {
                    upsertLocation(
                            retailer.id(),
                            record,
                            importColumns,
                            defaultStoreFormatId,
                            dataSourceId
                    );

                    rowsSaved++;
                } catch (IllegalArgumentException exception) {
                    log.warn(
                            "Preskočena lokacija u CSV redu {}: {}",
                            record.getRecordNumber(),
                            exception.getMessage()
                    );
                }
            }
        } catch (IOException exception) {
            dataSourceRepository.markFailed(
                    dataSourceId,
                    "Čitanje CSV fajla sa poslovnicama nije uspelo: "
                            + exception.getMessage()
            );
            throw new IllegalStateException(
                    "Čitanje CSV fajla sa poslovnicama nije uspelo.",
                    exception
            );
        } catch (RuntimeException exception) {
            dataSourceRepository.markFailed(
                    dataSourceId,
                    exception.getMessage()
            );
            throw exception;
        }

        String status =
                rowsRead == rowsSaved
                        ? "SUCCEEDED"
                        : "SUCCEEDED_WITH_ERRORS";

        dataSourceRepository.markSucceeded(
                dataSourceId,
                status,
                null,
                null,
                rowsRead,
                rowsSaved
        );

        return new RetailerLocationImportResult(
                retailer.code(),
                rowsRead,
                rowsSaved,
                rowsRead - rowsSaved,
                status
        );
    }

    public RetailerLocationImportResult importVerifiedLocations(
            String retailerCode,
            List<VerifiedRetailerLocation> locations,
            RetailerLocationSource source
    ) {
        Retailer retailer = retailerRepository.findByCode(retailerCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prodavnica nije pronađena: " + retailerCode
                ));

        if (locations == null || locations.isEmpty()) {
            throw new IllegalArgumentException(
                    "Zvanični izvor nije vratio nijednu lokaciju."
            );
        }

        RetailerLocationSource requiredSource = requiredSource(source);
        Long dataSourceId = ensureLocationDataSourceId(
                retailer.id(),
                requiredSource
        );

        if (!dataSourceRepository.tryMarkRunning(dataSourceId)) {
            throw new IllegalStateException(
                    "Import lokacija za ovaj izvor je već u toku."
            );
        }

        try {
            OffsetDateTime syncStartedAt = jdbcClient.sql("SELECT NOW()")
                    .query(OffsetDateTime.class)
                    .single();
            Set<CoordinateKey> duplicateCoordinates =
                    findDuplicateCoordinates(locations);

            int rowsSaved = 0;

            for (VerifiedRetailerLocation location : locations) {
                try {
                String externalCode = requiredText(
                        location.externalCode(),
                        "externalCode"
                );
                String name = requiredText(location.name(), "name");
                String address = requiredText(
                        location.address(),
                        "address"
                );
                String city = requiredText(location.city(), "city");
                String storeFormatCode = normalizeStoreFormatCode(
                        requiredText(
                                location.storeFormatCode(),
                                "storeFormatCode"
                        )
                );
                String storeFormatName = requiredText(
                        location.storeFormatName(),
                        "storeFormatName"
                );
                Coordinates coordinates = new Coordinates(
                        coordinate(
                                location.latitude(),
                                "latitude",
                                -90,
                                90
                        ),
                        coordinate(
                                location.longitude(),
                                "longitude",
                                -180,
                                180
                        )
                );
                boolean duplicateCoordinate = duplicateCoordinates.contains(
                        CoordinateKey.of(coordinates)
                );
                Long storeFormatId = ensureStoreFormatId(
                        retailer.id(),
                        storeFormatCode,
                        storeFormatName
                );

                upsertWithCoordinates(
                        retailer.id(),
                        storeFormatId,
                        externalCode,
                        name,
                        address,
                        city,
                        coordinates,
                        location.active(),
                        true,
                        requiredSource.geocodingSource(),
                        requiredSource.sourceUrl(),
                        requiredSource.pricingEligible()
                                && !duplicateCoordinate,
                        duplicateCoordinate
                                ? "DUPLICATE_OFFICIAL_COORDINATES"
                                : requiredSource
                                .pricingIneligibilityReason(),
                        duplicateCoordinate
                );
                markLocationSource(
                        retailer.id(),
                        externalCode,
                        dataSourceId,
                        true
                );
                rowsSaved++;
                } catch (IllegalArgumentException exception) {
                    log.warn(
                            "Preskočena zvanična lokacija {}: {}",
                            location == null ? null : location.externalCode(),
                            exception.getMessage()
                    );
                }
            }

            if (rowsSaved == locations.size()) {
                deactivateMissingLocations(dataSourceId, syncStartedAt);
            }

            String status = rowsSaved == locations.size()
                    ? "SUCCEEDED"
                    : "SUCCEEDED_WITH_ERRORS";

            dataSourceRepository.markSucceeded(
                    dataSourceId,
                    status,
                    null,
                    null,
                    locations.size(),
                    rowsSaved
            );

            return new RetailerLocationImportResult(
                    retailer.code(),
                    locations.size(),
                    rowsSaved,
                    locations.size() - rowsSaved,
                    status
            );
        } catch (RuntimeException exception) {
            dataSourceRepository.markFailed(
                    dataSourceId,
                    exception.getMessage()
            );
            throw exception;
        }
    }

    private void upsertLocation(
            Long retailerId,
            CSVRecord record,
            ImportColumns importColumns,
            Long defaultStoreFormatId,
            Long dataSourceId
    ) {
        String externalCode = requiredText(
                record,
                "external_code"
        );

        String name = requiredText(
                record,
                "name"
        );

        String address = requiredText(
                record,
                "address"
        );

        String city = requiredText(
                record,
                "city"
        );

        boolean storeFormatProvided =
                importColumns.hasStoreFormatColumns();

        Long storeFormatId = defaultStoreFormatId;

        if (storeFormatProvided) {
            String storeFormatCode = normalizeStoreFormatCode(
                    requiredText(record, "store_format_code")
            );

            String storeFormatName = requiredText(
                    record,
                    "store_format_name"
            );

            storeFormatId = ensureStoreFormatId(
                    retailerId,
                    storeFormatCode,
                    storeFormatName
            );
        }

        Coordinates coordinates = parseCoordinates(
                record,
                importColumns
        );

        boolean active = parseActive(
                record.get("active")
        );

        if (coordinates == null) {
            upsertWithoutCoordinates(
                    retailerId,
                    storeFormatId,
                    externalCode,
                    name,
                    address,
                    city,
                    active,
                    storeFormatProvided
            );

            markLocationSource(
                    retailerId,
                    externalCode,
                    dataSourceId,
                    false
            );

            return;
        }

        upsertWithCoordinates(
                retailerId,
                storeFormatId,
                externalCode,
                name,
                address,
                city,
                coordinates,
                active,
                storeFormatProvided,
                "LOCATION_IMPORT",
                null,
                false,
                "MANUAL_FORMAT_NOT_VERIFIED",
                false
        );

        markLocationSource(
                retailerId,
                externalCode,
                dataSourceId,
                true
        );
    }

    private Long ensureLocationDataSourceId(Long retailerId) {
        return ensureLocationDataSourceId(
                retailerId,
                new RetailerLocationSource(
                        "MANUAL_LOCATION_CSV",
                        "STANDARD_STORE_CSV",
                        null,
                        "LOCATION_IMPORT",
                        false,
                        "MANUAL_FORMAT_NOT_VERIFIED"
                )
        );
    }

    private Long ensureLocationDataSourceId(
            Long retailerId,
            RetailerLocationSource source
    ) {
        SourceHealthPolicy policy = sourceHealthPolicy(source.code());
        return jdbcClient.sql("""
                        INSERT INTO app.retailer_data_source (
                            retailer_id,
                            code,
                            source_type,
                            parser_profile,
                            source_url,
                            encoding,
                            delimiter,
                            expected_min_rows_saved,
                            max_success_age_hours,
                            minimum_volume_ratio,
                            active
                        )
                        VALUES (
                            ?,
                            ?,
                            'STORE_LOCATIONS',
                            ?,
                            ?,
                            'UTF-8',
                            ';',
                            ?,
                            ?,
                            ?,
                            TRUE
                        )
                        ON CONFLICT (retailer_id, code)
                        DO UPDATE SET
                            parser_profile = EXCLUDED.parser_profile,
                            source_url = EXCLUDED.source_url,
                            expected_min_rows_saved =
                                EXCLUDED.expected_min_rows_saved,
                            max_success_age_hours =
                                EXCLUDED.max_success_age_hours,
                            minimum_volume_ratio =
                                EXCLUDED.minimum_volume_ratio,
                            active = TRUE,
                            updated_at = NOW()
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, source.code())
                .param(3, source.parserProfile())
                .param(4, source.sourceUrl(), Types.VARCHAR)
                .param(5, policy.expectedMinimumRows(), Types.INTEGER)
                .param(6, policy.maximumAgeHours(), Types.INTEGER)
                .param(7, policy.minimumVolumeRatio())
                .query(Long.class)
                .single();
    }

    private SourceHealthPolicy sourceHealthPolicy(String sourceCode) {
        return switch (sourceCode) {
            case "OFFICIAL_LIDL_LOCATION_API" ->
                    new SourceHealthPolicy(80, 192, new BigDecimal("0.8000"));
            case "OFFICIAL_DIS_LOCATION_API" ->
                    new SourceHealthPolicy(45, 192, new BigDecimal("0.8000"));
            case "OFFICIAL_MAXI_STORE_LOCATOR" ->
                    new SourceHealthPolicy(500, 192, new BigDecimal("0.8000"));
            case "OFFICIAL_IDEA_RODA_STORE_LOCATORS" ->
                    new SourceHealthPolicy(220, 192, new BigDecimal("0.8000"));
            case "OFFICIAL_UNIVEREXPORT_LOCATION_API" ->
                    new SourceHealthPolicy(180, 192, new BigDecimal("0.8000"));
            case "OFFICIAL_EUROPROM_STORE_PAGE" ->
                    new SourceHealthPolicy(40, 192, new BigDecimal("0.8000"));
            default -> new SourceHealthPolicy(
                    null,
                    null,
                    new BigDecimal("0.6000")
            );
        };
    }

    private void deactivateMissingLocations(
            Long dataSourceId,
            OffsetDateTime syncStartedAt
    ) {
        jdbcClient.sql("""
                    UPDATE app.store
                    SET active = FALSE,
                        pricing_eligible = FALSE,
                        pricing_ineligibility_reason = 'INACTIVE',
                        updated_at = NOW()
                    WHERE data_source_id = ?
                      AND (
                          source_last_seen_at IS NULL
                          OR source_last_seen_at < ?
                      )
                    """)
                .param(1, dataSourceId)
                .param(2, syncStartedAt)
                .update();
    }

    private void markLocationSource(
            Long retailerId,
            String externalCode,
            Long dataSourceId,
            boolean verified
    ) {
        jdbcClient.sql("""
                    UPDATE app.store
                    SET data_source_id = ?,
                        source_record_key = external_code,
                        source_last_seen_at = NOW(),
                        verified_at = CASE
                            WHEN ? THEN NOW()
                            ELSE verified_at
                        END,
                        updated_at = NOW()
                    WHERE retailer_id = ?
                      AND external_code = ?
                    """)
                .param(1, dataSourceId)
                .param(2, verified)
                .param(3, retailerId)
                .param(4, externalCode)
                .update();
    }

    private void upsertWithoutCoordinates(
            Long retailerId,
            Long storeFormatId,
            String externalCode,
            String name,
            String address,
            String city,
            boolean active,
            boolean storeFormatProvided
    ) {
        jdbcClient.sql("""
                    INSERT INTO app.store AS existing_store (
                        retailer_id,
                        store_format_id,
                        external_code,
                        name,
                        address,
                        city,
                        location,
                        active,
                        pricing_eligible,
                        pricing_ineligibility_reason
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?, NULL, ?,
                        FALSE, 'MISSING_COORDINATES'
                    )
                    ON CONFLICT (retailer_id, external_code)
                    DO UPDATE SET
                        store_format_id = CASE
                        WHEN ? THEN EXCLUDED.store_format_id
                           ELSE existing_store.store_format_id
                           END,
                        name = EXCLUDED.name,
                        location = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.location
                            ELSE NULL
                        END,
                        geocoding_candidate = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_candidate
                            ELSE NULL
                        END,
                        geocoding_status = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_status
                            ELSE 'PENDING'
                        END,
                        geocoding_query = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_query
                            ELSE NULL
                        END,
                        geocoding_source = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_source
                            ELSE NULL
                        END,
                        geocoding_source_reference = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_source_reference
                            ELSE NULL
                        END,
                        geocoding_matched_address = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_matched_address
                            ELSE NULL
                        END,
                        geocoding_confidence = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_confidence
                            ELSE NULL
                        END,
                        geocoding_suspicious_reason = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_suspicious_reason
                            ELSE NULL
                        END,
                        geocoded_at = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoded_at
                            ELSE NULL
                        END,
                        geocoding_review_note = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_review_note
                            ELSE NULL
                        END,
                        geocoding_reviewed_at = CASE
                            WHEN BTRIM(existing_store.address)
                                    = BTRIM(EXCLUDED.address)
                             AND BTRIM(existing_store.city)
                                    = BTRIM(EXCLUDED.city)
                                THEN existing_store.geocoding_reviewed_at
                            ELSE NULL
                        END,
                        address = EXCLUDED.address,
                        city = EXCLUDED.city,
                        active = EXCLUDED.active,
                        pricing_eligible = FALSE,
                        pricing_ineligibility_reason =
                            'MISSING_COORDINATES',
                        updated_at = NOW()
                    """)
                .param(1, retailerId)
                .param(2, storeFormatId)
                .param(3, externalCode)
                .param(4, name)
                .param(5, address)
                .param(6, city)
                .param(7, active)
                .param(8, storeFormatProvided)
                .update();
    }

    private void upsertWithCoordinates(
            Long retailerId,
            Long storeFormatId,
            String externalCode,
            String name,
            String address,
            String city,
            Coordinates coordinates,
            boolean active,
            boolean storeFormatProvided,
            String geocodingSource,
            String sourceReference,
            boolean pricingEligible,
            String pricingIneligibilityReason,
            boolean forcePricingIneligible
    ) {
        jdbcClient.sql("""
                    INSERT INTO app.store AS existing_store (
                        retailer_id,
                        store_format_id,
                        external_code,
                        name,
                        address,
                        city,
                        location,
                        geocoding_candidate,
                        geocoding_status,
                        geocoding_query,
                        geocoding_source,
                        geocoding_source_reference,
                        geocoding_matched_address,
                        geocoding_confidence,
                        geocoded_at,
                        geocoding_review_note,
                        geocoding_reviewed_at,
                        active,
                        pricing_eligible,
                        pricing_ineligibility_reason
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?,
                        ST_SetSRID(
                            ST_MakePoint(?, ?),
                            4326
                        )::geography,
                        ST_SetSRID(
                            ST_MakePoint(?, ?),
                            4326
                        )::geography,
                        'MANUALLY_VERIFIED',
                        LOWER(BTRIM(?)) || ', ' || LOWER(BTRIM(?)),
                        ?,
                        ?,
                        BTRIM(?) || ', ' || BTRIM(?),
                        1.0000,
                        NOW(),
                        'Koordinate su potvrđene kroz location import.',
                        NOW(),
                        ?, ?, ?
                    )
                    ON CONFLICT (retailer_id, external_code)
                    DO UPDATE SET
                        store_format_id = CASE
                               WHEN ? THEN EXCLUDED.store_format_id
                               ELSE existing_store.store_format_id
                           END,
                        name = EXCLUDED.name,
                        address = EXCLUDED.address,
                        city = EXCLUDED.city,
                        location = EXCLUDED.location,
                        geocoding_candidate =
                            EXCLUDED.geocoding_candidate,
                        geocoding_status = EXCLUDED.geocoding_status,
                        geocoding_query = EXCLUDED.geocoding_query,
                        geocoding_source = EXCLUDED.geocoding_source,
                        geocoding_source_reference =
                            EXCLUDED.geocoding_source_reference,
                        geocoding_matched_address =
                            EXCLUDED.geocoding_matched_address,
                        geocoding_confidence =
                            EXCLUDED.geocoding_confidence,
                        geocoding_suspicious_reason = NULL,
                        geocoded_at = EXCLUDED.geocoded_at,
                        geocoding_review_note =
                            EXCLUDED.geocoding_review_note,
                        geocoding_reviewed_at =
                            EXCLUDED.geocoding_reviewed_at,
                        active = EXCLUDED.active,
                        pricing_eligible = CASE
                            WHEN ? THEN FALSE
                            WHEN EXISTS (
                                SELECT 1
                                FROM app.current_price_offer AS offer
                                WHERE offer.store_id = existing_store.id
                                  AND offer.scope_type = 'STORE'
                            ) THEN TRUE
                            ELSE EXCLUDED.pricing_eligible
                        END,
                        pricing_ineligibility_reason = CASE
                            WHEN ?
                                THEN EXCLUDED.pricing_ineligibility_reason
                            WHEN EXISTS (
                                SELECT 1
                                FROM app.current_price_offer AS offer
                                WHERE offer.store_id = existing_store.id
                                  AND offer.scope_type = 'STORE'
                            ) THEN NULL
                            ELSE EXCLUDED.pricing_ineligibility_reason
                        END,
                        updated_at = NOW()
                    """)
                .param(1, retailerId)
                .param(2, storeFormatId)
                .param(3, externalCode)
                .param(4, name)
                .param(5, address, Types.VARCHAR)
                .param(6, city, Types.VARCHAR)
                .param(7, coordinates.longitude())
                .param(8, coordinates.latitude())
                .param(9, coordinates.longitude())
                .param(10, coordinates.latitude())
                .param(11, address)
                .param(12, city)
                .param(13, geocodingSource)
                .param(14, sourceReference, Types.VARCHAR)
                .param(15, address)
                .param(16, city)
                .param(17, active)
                .param(18, pricingEligible)
                .param(19, pricingIneligibilityReason, Types.VARCHAR)
                .param(20, storeFormatProvided)
                .param(21, forcePricingIneligible)
                .param(22, forcePricingIneligible)
                .update();
    }

    private Set<CoordinateKey> findDuplicateCoordinates(
            List<VerifiedRetailerLocation> locations
    ) {
        Map<CoordinateKey, Integer> counts = new HashMap<>();

        for (VerifiedRetailerLocation location : locations) {
            if (location == null
                    || !Double.isFinite(location.latitude())
                    || !Double.isFinite(location.longitude())) {
                continue;
            }

            counts.merge(
                    CoordinateKey.of(
                            location.latitude(),
                            location.longitude()
                    ),
                    1,
                    Integer::sum
            );
        }

        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long ensureStoreFormatId(
            Long retailerId,
            String code,
            String name
    ) {
        return jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, ?, ?)
                        ON CONFLICT (retailer_id, code)
                        DO UPDATE SET
                            name = EXCLUDED.name,
                            active = TRUE,
                            updated_at = NOW()
                        RETURNING id
                        """)
                .param(retailerId)
                .param(code)
                .param(name)
                .query(Long.class)
                .single();
    }

    private ImportColumns validateHeaders(CSVParser parser) {
        Set<String> actualHeaders =
                parser.getHeaderNames()
                        .stream()
                        .map(header ->
                                header.trim()
                                        .toLowerCase(Locale.ROOT)
                        )
                        .collect(Collectors.toSet());

        Set<String> missingHeaders =
                REQUIRED_HEADERS.stream()
                        .filter(header ->
                                !actualHeaders.contains(header)
                        )
                        .collect(Collectors.toSet());

        if (!missingHeaders.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nedostaju CSV kolone: " + missingHeaders
            );
        }

        boolean hasLatitude = actualHeaders.contains("latitude");
        boolean hasLongitude = actualHeaders.contains("longitude");

        if (hasLatitude != hasLongitude) {
            throw new IllegalArgumentException(
                    "CSV mora sadržati obe kolone: latitude i longitude"
            );
        }

        boolean hasStoreFormatCode =
                actualHeaders.contains("store_format_code");
        boolean hasStoreFormatName =
                actualHeaders.contains("store_format_name");

        if (hasStoreFormatCode != hasStoreFormatName) {
            throw new IllegalArgumentException(
                    "CSV mora sadržati obe kolone: "
                            + "store_format_code i store_format_name"
            );
        }

        return new ImportColumns(
                hasStoreFormatCode,
                hasLatitude
        );
    }

    private Coordinates parseCoordinates(
            CSVRecord record,
            ImportColumns importColumns
    ) {
        if (!importColumns.hasCoordinateColumns()) {
            return null;
        }

        String rawLatitude = nullableText(record.get("latitude"));
        String rawLongitude = nullableText(record.get("longitude"));

        if (rawLatitude == null && rawLongitude == null) {
            return null;
        }

        if (rawLatitude == null || rawLongitude == null) {
            throw new IllegalArgumentException(
                    "Latitude i longitude moraju biti uneti zajedno"
            );
        }

        return new Coordinates(
                parseCoordinate(
                        rawLatitude,
                        "latitude",
                        -90,
                        90
                ),
                parseCoordinate(
                        rawLongitude,
                        "longitude",
                        -180,
                        180
                )
        );
    }

    private double parseCoordinate(
            String rawValue,
            String columnName,
            double minimum,
            double maximum
    ) {
        String normalizedValue =
                rawValue.replace(',', '.');

        final double value;

        try {
            value = Double.parseDouble(normalizedValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Neispravna koordinata "
                            + columnName
                            + ": "
                            + rawValue
            );
        }

        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    columnName
                            + " mora biti između "
                            + minimum
                            + " i "
                            + maximum
            );
        }

        return value;
    }

    private double coordinate(
            double value,
            String fieldName,
            double minimum,
            double maximum
    ) {
        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    fieldName
                            + " mora biti između "
                            + minimum
                            + " i "
                            + maximum
            );
        }

        return value;
    }

    private RetailerLocationSource requiredSource(
            RetailerLocationSource source
    ) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "Poreklo zvaničnih lokacija je obavezno."
            );
        }

        return new RetailerLocationSource(
                normalizeStoreFormatCode(
                        requiredText(source.code(), "source.code")
                ),
                requiredText(
                        source.parserProfile(),
                        "source.parserProfile"
                ),
                requiredText(source.sourceUrl(), "source.sourceUrl"),
                normalizeStoreFormatCode(
                        requiredText(
                                source.geocodingSource(),
                                "source.geocodingSource"
                        )
                ),
                source.pricingEligible(),
                validatePricingIneligibilityReason(source)
        );
    }

    private String validatePricingIneligibilityReason(
            RetailerLocationSource source
    ) {
        if (source.pricingEligible()) {
            if (source.pricingIneligibilityReason() != null) {
                throw new IllegalArgumentException(
                        "Izvor podoban za cene ne sme imati razlog zabrane."
                );
            }

            return null;
        }

        return normalizeStoreFormatCode(requiredText(
                source.pricingIneligibilityReason(),
                "source.pricingIneligibilityReason"
        ));
    }

    private String normalizeStoreFormatCode(String value) {
        String normalizedValue = value
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace(' ', '_');

        if (!normalizedValue.matches("[A-Z0-9][A-Z0-9_-]{0,99}")) {
            throw new IllegalArgumentException(
                    "Neispravan store_format_code: " + value
            );
        }

        return normalizedValue;
    }

    private boolean parseActive(String value) {
        String normalizedValue = nullableText(value);

        if (normalizedValue == null) {
            return true;
        }

        return switch (
                normalizedValue.toLowerCase(Locale.ROOT)
                ) {
            case "true", "1", "yes", "da" -> true;
            case "false", "0", "no", "ne" -> false;

            default -> throw new IllegalArgumentException(
                    "Neispravna active vrednost: " + value
            );
        };
    }

    private String requiredText(
            CSVRecord record,
            String columnName
    ) {
        String value = nullableText(
                record.get(columnName)
        );

        if (value == null) {
            throw new IllegalArgumentException(
                    "Obavezna kolona je prazna: "
                            + columnName
            );
        }

        return value;
    }

    private String requiredText(String value, String fieldName) {
        String requiredValue = nullableText(value);

        if (requiredValue == null) {
            throw new IllegalArgumentException(
                    "Obavezno polje je prazno: " + fieldName
            );
        }

        return requiredValue;
    }

    private String nullableText(String value) {
        if (value == null) {
            return null;
        }

        String trimmedValue = value.trim();

        return trimmedValue.isEmpty()
                ? null
                : trimmedValue;
    }

    private Reader createBomAwareReader(
            InputStream inputStream
    ) throws IOException {
        byte[] firstBytes = inputStream.readNBytes(3);

        boolean hasUtf8Bom =
                firstBytes.length == 3
                        && (firstBytes[0] & 0xFF) == 0xEF
                        && (firstBytes[1] & 0xFF) == 0xBB
                        && (firstBytes[2] & 0xFF) == 0xBF;

        InputStream completeStream;

        if (hasUtf8Bom) {
            completeStream = inputStream;
        } else {
            completeStream =
                    new java.io.SequenceInputStream(
                            new ByteArrayInputStream(firstBytes),
                            inputStream
                    );
        }

        return new InputStreamReader(
                completeStream,
                StandardCharsets.UTF_8
        );
    }

    private record ImportColumns(
            boolean hasStoreFormatColumns,
            boolean hasCoordinateColumns
    ) {
    }

    private record Coordinates(
            double latitude,
            double longitude
    ) {
    }

    private record CoordinateKey(long latitude, long longitude) {

        private static final double PRECISION = 1_000_000D;

        static CoordinateKey of(Coordinates coordinates) {
            return of(coordinates.latitude(), coordinates.longitude());
        }

        static CoordinateKey of(double latitude, double longitude) {
            return new CoordinateKey(
                    Math.round(latitude * PRECISION),
                    Math.round(longitude * PRECISION)
            );
        }
    }

    private record SourceHealthPolicy(
            Integer expectedMinimumRows,
            Integer maximumAgeHours,
            BigDecimal minimumVolumeRatio
    ) {
    }
}
