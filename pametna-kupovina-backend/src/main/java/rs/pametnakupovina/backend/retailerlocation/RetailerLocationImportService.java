package rs.pametnakupovina.backend.retailerlocation;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.retailer.Retailer;
import rs.pametnakupovina.backend.retailer.RetailerRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.Locale;
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

    public RetailerLocationImportService(
            JdbcClient jdbcClient,
            RetailerRepository retailerRepository
    ) {
        this.jdbcClient = jdbcClient;
        this.retailerRepository = retailerRepository;
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
                            defaultStoreFormatId
                    );

                    rowsSaved++;
                } catch (RuntimeException exception) {
                    log.warn(
                            "Preskočena lokacija u CSV redu {}: {}",
                            record.getRecordNumber(),
                            exception.getMessage()
                    );
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Čitanje CSV fajla sa poslovnicama nije uspelo.",
                    exception
            );
        }

        String status =
                rowsRead == rowsSaved
                        ? "SUCCEEDED"
                        : "SUCCEEDED_WITH_ERRORS";

        return new RetailerLocationImportResult(
                retailer.code(),
                rowsRead,
                rowsSaved,
                rowsRead - rowsSaved,
                status
        );
    }

    private void upsertLocation(
            Long retailerId,
            CSVRecord record,
            ImportColumns importColumns,
            Long defaultStoreFormatId
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

        Long storeFormatId = defaultStoreFormatId;

        if (importColumns.hasStoreFormatColumns()) {
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
                    active
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
                active
        );
    }

    private void upsertWithoutCoordinates(
            Long retailerId,
            Long storeFormatId,
            String externalCode,
            String name,
            String address,
            String city,
            boolean active
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
                        active
                    )
                    VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                    ON CONFLICT (retailer_id, external_code)
                    DO UPDATE SET
                        store_format_id = EXCLUDED.store_format_id,
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
                        updated_at = NOW()
                    """)
                .param(1, retailerId)
                .param(2, storeFormatId)
                .param(3, externalCode)
                .param(4, name)
                .param(5, address)
                .param(6, city)
                .param(7, active)
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
            boolean active
    ) {
        jdbcClient.sql("""
                    INSERT INTO app.store (
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
                        geocoding_matched_address,
                        geocoding_confidence,
                        geocoded_at,
                        geocoding_review_note,
                        geocoding_reviewed_at,
                        active
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
                        'LOCATION_IMPORT',
                        BTRIM(?) || ', ' || BTRIM(?),
                        1.0000,
                        NOW(),
                        'Koordinate su potvrđene kroz location import.',
                        NOW(),
                        ?
                    )
                    ON CONFLICT (retailer_id, external_code)
                    DO UPDATE SET
                        store_format_id = EXCLUDED.store_format_id,
                        name = EXCLUDED.name,
                        address = EXCLUDED.address,
                        city = EXCLUDED.city,
                        location = EXCLUDED.location,
                        geocoding_candidate =
                            EXCLUDED.geocoding_candidate,
                        geocoding_status = EXCLUDED.geocoding_status,
                        geocoding_query = EXCLUDED.geocoding_query,
                        geocoding_source = EXCLUDED.geocoding_source,
                        geocoding_source_reference = NULL,
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
                .param(13, address)
                .param(14, city)
                .param(15, active)
                .update();
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
}
