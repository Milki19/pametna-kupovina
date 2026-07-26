package rs.pametnakupovina.backend.priceimport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rs.pametnakupovina.backend.retailer.Retailer;
import rs.pametnakupovina.backend.retailer.RetailerRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class PriceImportService {

    private static final Logger log =
            LoggerFactory.getLogger(PriceImportService.class);

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';')
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get();

    private final JdbcClient jdbcClient;
    private final RetailerRepository retailerRepository;
    private final HttpClient httpClient;

    public PriceImportService(
            JdbcClient jdbcClient,
            RetailerRepository retailerRepository
    ) {
        this.jdbcClient = jdbcClient;
        this.retailerRepository = retailerRepository;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ImportResult importPrices(String retailerCode, int maxRows) {
        Retailer retailer = retailerRepository.findByCode(retailerCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prodavnica nije pronađena: " + retailerCode
                ));

        if (retailer.datasetUrl() == null || retailer.datasetUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "Prodavnica nema podešen dataset URL: " + retailerCode
            );
        }

        Long importRunId = startImport(
                retailer.id(),
                retailer.datasetUrl()
        );

        int rowsRead = 0;
        int rowsSaved = 0;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(retailer.datasetUrl()))
                    .header("User-Agent", "PametnaKupovina/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                response.body().close();

                throw new IllegalStateException(
                        "Preuzimanje CSV fajla nije uspelo. HTTP status: "
                                + response.statusCode()
                );
            }

            try (
                    InputStream inputStream = response.body();
                    Reader reader = createBomAwareReader(inputStream);
                    CSVParser parser = CSV_FORMAT.parse(reader)
            ) {
                for (CSVRecord record : parser) {
                    if (rowsRead >= maxRows) {
                        break;
                    }

                    rowsRead++;

                    try {
                        saveRecord(retailer.id(), importRunId, record);
                        rowsSaved++;
                    } catch (RuntimeException exception) {
                        log.warn(
                                "Preskočen CSV red {}: {}",
                                record.getRecordNumber(),
                                exception.getMessage()
                        );
                    }
                }
            }

            completeImport(importRunId, rowsRead, rowsSaved);

            return new ImportResult(
                    importRunId,
                    rowsRead,
                    rowsSaved,
                    rowsRead - rowsSaved,
                    "SUCCEEDED"
            );
        } catch (Exception exception) {
            failImport(
                    importRunId,
                    rowsRead,
                    rowsSaved,
                    exception.getMessage()
            );

            throw new IllegalStateException(
                    "Import cena nije uspeo: " + exception.getMessage(),
                    exception
            );
        }
    }

    @Transactional
    protected void saveRecord(
            Long retailerId,
            Long importRunId,
            CSVRecord record
    ) {
        String productName = requiredText(
                record,
                "Naziv proizvoda"
        );

        String barcode = requiredText(
                record,
                "Barkod proizvoda"
        );

        Long retailerProductId = upsertProduct(
                retailerId,
                record.get("KATEGORIJA"),
                record.get("NAZIV KATEGORIJE"),
                productName,
                record.get("Robna marka"),
                barcode,
                record.get("Jedinica mere")
        );

        insertPriceObservation(
                retailerProductId,
                importRunId,
                record.get("Naziv trgovca - formata*"),
                parseRequiredDate(record.get("Datum cenovnika")),
                parseDecimal(record.get("Redovna cena")),
                parseDecimal(record.get("Cena po jedinici mere")),
                parseDecimal(record.get("Snižena cena")),
                parseDate(record.get("Datum početka sniženja")),
                parseDate(record.get("Datum kraja sniženja")),
                parseDecimal(record.get("Stopa PDV"))
        );
    }

    private Long startImport(Long retailerId, String sourceUrl) {
        return jdbcClient.sql("""
                        INSERT INTO app.import_run (
                            retailer_id,
                            source_url,
                            status
                        )
                        VALUES (?, ?, 'RUNNING')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, sourceUrl)
                .query(Long.class)
                .single();
    }

    private Long upsertProduct(
            Long retailerId,
            String categoryCode,
            String categoryName,
            String productName,
            String brand,
            String barcode,
            String unit
    ) {
        return jdbcClient.sql("""
                    INSERT INTO app.retailer_product (
                        retailer_id,
                        category_code,
                        category_name,
                        name,
                        brand,
                        barcode,
                        unit
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (retailer_id, barcode)
                        WHERE barcode IS NOT NULL
                          AND barcode <> ''
                    DO UPDATE SET
                        category_code = EXCLUDED.category_code,
                        category_name = EXCLUDED.category_name,
                        name = EXCLUDED.name,
                        brand = EXCLUDED.brand,
                        unit = EXCLUDED.unit
                    RETURNING id
                    """)
                .param(1, retailerId)
                .param(2, nullableText(categoryCode), Types.VARCHAR)
                .param(3, nullableText(categoryName), Types.VARCHAR)
                .param(4, productName)
                .param(5, nullableText(brand), Types.VARCHAR)
                .param(6, barcode)
                .param(7, nullableText(unit), Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    private void insertPriceObservation(
            Long retailerProductId,
            Long importRunId,
            String retailerFormatName,
            LocalDate priceDate,
            BigDecimal regularPrice,
            BigDecimal unitPrice,
            BigDecimal discountedPrice,
            LocalDate discountStartDate,
            LocalDate discountEndDate,
            BigDecimal vatRate
    ) {
        jdbcClient.sql("""
                    INSERT INTO app.price_observation (
                        retailer_product_id,
                        import_run_id,
                        retailer_format_name,
                        price_date,
                        regular_price,
                        unit_price,
                        discounted_price,
                        discount_start,
                        discount_end,
                        vat_rate
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (
                        retailer_product_id,
                        price_date,
                        retailer_format_name
                    )
                    DO UPDATE SET
                        import_run_id = EXCLUDED.import_run_id,
                        regular_price = EXCLUDED.regular_price,
                        unit_price = EXCLUDED.unit_price,
                        discounted_price = EXCLUDED.discounted_price,
                        discount_start = EXCLUDED.discount_start,
                        discount_end = EXCLUDED.discount_end,
                        vat_rate = EXCLUDED.vat_rate
                    """)
                .param(1, retailerProductId)
                .param(2, importRunId)
                .param(
                        3,
                        nullableText(retailerFormatName),
                        Types.VARCHAR
                )
                .param(4, priceDate, Types.DATE)
                .param(5, regularPrice, Types.NUMERIC)
                .param(6, unitPrice, Types.NUMERIC)
                .param(7, discountedPrice, Types.NUMERIC)
                .param(8, discountStartDate, Types.DATE)
                .param(9, discountEndDate, Types.DATE)
                .param(10, vatRate, Types.NUMERIC)
                .update();
    }

    private void completeImport(
            Long importRunId,
            int rowsRead,
            int rowsSaved
    ) {
        jdbcClient.sql("""
                        UPDATE app.import_run
                        SET status = 'SUCCEEDED',
                            finished_at = NOW(),
                            rows_read = ?,
                            rows_saved = ?
                        WHERE id = ?
                        """)
                .param(1, rowsRead)
                .param(2, rowsSaved)
                .param(3, importRunId)
                .update();
    }

    private void failImport(
            Long importRunId,
            int rowsRead,
            int rowsSaved,
            String errorMessage
    ) {
        jdbcClient.sql("""
                        UPDATE app.import_run
                        SET status = 'FAILED',
                            finished_at = NOW(),
                            rows_read = ?,
                            rows_saved = ?,
                            error_message = ?
                        WHERE id = ?
                        """)
                .param(1, rowsRead)
                .param(2, rowsSaved)
                .param(
                        3,
                        shortenErrorMessage(errorMessage),
                        Types.VARCHAR
                )
                .param(4, importRunId)
                .update();
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
            completeStream = new java.io.SequenceInputStream(
                    new ByteArrayInputStream(firstBytes),
                    inputStream
            );
        }

        return new InputStreamReader(
                completeStream,
                StandardCharsets.UTF_8
        );
    }

    private String requiredText(
            CSVRecord record,
            String columnName
    ) {
        String value = nullableText(record.get(columnName));

        if (value == null) {
            throw new IllegalArgumentException(
                    "Obavezna kolona je prazna: " + columnName
            );
        }

        return value;
    }

    private String nullableText(String value) {
        if (value == null) {
            return null;
        }

        String trimmedValue = value.trim();

        return trimmedValue.isEmpty() ? null : trimmedValue;
    }

    private BigDecimal parseDecimal(String value) {
        String rawValue = nullableText(value);

        if (rawValue == null
                || rawValue.equals("-")
                || rawValue.equalsIgnoreCase("null")
                || rawValue.equalsIgnoreCase("n/a")) {
            return null;
        }

        String compactValue = rawValue
                .replace("\u00A0", "")
                .replace(" ", "");

        int lastComma = compactValue.lastIndexOf(',');
        int lastDot = compactValue.lastIndexOf('.');

        String normalizedValue;

        if (lastComma >= 0 && lastDot >= 0) {
            /*
             * Ako postoje i tačka i zarez, poslednji separator
             * posmatramo kao decimalni:
             *
             * 1.299,90 -> 1299.90
             * 1,299.90 -> 1299.90
             */
            if (lastComma > lastDot) {
                normalizedValue = compactValue
                        .replace(".", "")
                        .replace(',', '.');
            } else {
                normalizedValue = compactValue
                        .replace(",", "");
            }
        } else if (lastComma >= 0) {
            normalizedValue = normalizeRepeatedSeparator(
                    compactValue,
                    ','
            );
        } else if (lastDot >= 0) {
            normalizedValue = normalizeRepeatedSeparator(
                    compactValue,
                    '.'
            );
        } else {
            normalizedValue = compactValue;
        }

        try {
            return new BigDecimal(normalizedValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Neispravan decimalni broj: '"
                            + rawValue
                            + "', normalizovana vrednost: '"
                            + normalizedValue
                            + "'",
                    exception
            );
        }
    }

    private String normalizeRepeatedSeparator(
            String value,
            char separator
    ) {
        int firstPosition = value.indexOf(separator);
        int lastPosition = value.lastIndexOf(separator);

        if (firstPosition == lastPosition) {
            return separator == ','
                    ? value.replace(',', '.')
                    : value;
        }

        String separatorText = String.valueOf(separator);

        String integerPart = value
                .substring(0, lastPosition)
                .replace(separatorText, "");

        String decimalPart = value.substring(lastPosition + 1);

        return integerPart + "." + decimalPart;
    }

    private LocalDate parseDate(String value) {
        String normalizedValue = nullableText(value);

        if (normalizedValue == null) {
            return null;
        }

        return LocalDate.parse(
                normalizedValue,
                DATE_FORMAT
        );
    }

    private LocalDate parseRequiredDate(String value) {
        LocalDate date = parseDate(value);

        if (date == null) {
            throw new IllegalArgumentException(
                    "Datum cenovnika je obavezan"
            );
        }

        return date;
    }

    private String shortenErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Nepoznata greška";
        }

        return message.length() <= 1000
                ? message
                : message.substring(0, 1000);
    }
}