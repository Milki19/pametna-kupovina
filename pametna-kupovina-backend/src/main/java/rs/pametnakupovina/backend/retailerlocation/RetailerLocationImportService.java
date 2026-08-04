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
            "latitude",
            "longitude",
            "active"
    );

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

        Long storeFormatId =
                ensureDefaultStoreFormatId(retailer.id());

        int rowsRead = 0;
        int rowsSaved = 0;

        try (
                Reader reader = createBomAwareReader(inputStream);
                CSVParser parser = CSV_FORMAT.parse(reader)
        ) {
            validateHeaders(parser);

            for (CSVRecord record : parser) {
                if (rowsRead >= maxRows) {
                    break;
                }

                rowsRead++;

                try {
                    upsertLocation(
                            retailer.id(),
                            storeFormatId,
                            record
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
            Long storeFormatId,
            CSVRecord record
    ) {
        String externalCode = requiredText(
                record,
                "external_code"
        );

        String name = requiredText(
                record,
                "name"
        );

        String address = nullableText(
                record.get("address")
        );

        String city = nullableText(
                record.get("city")
        );

        double latitude = parseCoordinate(
                record,
                "latitude",
                -90,
                90
        );

        double longitude = parseCoordinate(
                record,
                "longitude",
                -180,
                180
        );

        boolean active = parseActive(
                record.get("active")
        );

        jdbcClient.sql("""
                    INSERT INTO app.store (
                        retailer_id,
                        store_format_id,
                        external_code,
                        name,
                        address,
                        city,
                        location,
                        active
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?,
                        ST_SetSRID(
                            ST_MakePoint(?, ?),
                            4326
                        )::geography,
                        ?
                    )
                    ON CONFLICT (retailer_id, external_code)
                    DO UPDATE SET
                        name = EXCLUDED.name,
                        address = EXCLUDED.address,
                        city = EXCLUDED.city,
                        location = EXCLUDED.location,
                        active = EXCLUDED.active,
                        updated_at = NOW()
                    """)
                .param(1, retailerId)
                .param(2, storeFormatId)
                .param(3, externalCode)
                .param(4, name)
                .param(5, address, Types.VARCHAR)
                .param(6, city, Types.VARCHAR)
                .param(7, longitude)
                .param(8, latitude)
                .param(9, active)
                .update();
    }

    private Long ensureDefaultStoreFormatId(Long retailerId) {
        return jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'STANDARD', 'Standardni format')
                        ON CONFLICT (retailer_id, code)
                        DO UPDATE SET code = EXCLUDED.code
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();
    }

    private void validateHeaders(CSVParser parser) {
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
    }

    private double parseCoordinate(
            CSVRecord record,
            String columnName,
            double minimum,
            double maximum
    ) {
        String rawValue = requiredText(
                record,
                columnName
        );

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
}
