package rs.pametnakupovina.backend.priceimport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.matching.ExactEanMatcher;
import rs.pametnakupovina.backend.matching.ParsedQuantity;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

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
            .setIgnoreHeaderCase(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .get();
    private static final int WRITE_BATCH_SIZE = 500;
    private static final int MAX_DETAILED_PARSE_ERROR_LOGS = 20;

    private final JdbcClient jdbcClient;
    private final RetailerRepository retailerRepository;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductQuantityParser productQuantityParser;
    private final ExactEanMatcher exactEanMatcher;
    private final HttpClient httpClient;
    private final TransactionTemplate transactionTemplate;

    public PriceImportService(
            JdbcClient jdbcClient,
            RetailerRepository retailerRepository,
            ProductNameNormalizer productNameNormalizer,
            ProductQuantityParser productQuantityParser,
            ExactEanMatcher exactEanMatcher,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcClient = jdbcClient;
        this.retailerRepository = retailerRepository;
        this.productNameNormalizer = productNameNormalizer;
        this.productQuantityParser = productQuantityParser;
        this.exactEanMatcher = exactEanMatcher;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);

        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ImportResult importPrices(String retailerCode, int maxRows) {
        Retailer retailer = retailerRepository.findByCode(retailerCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prodavnica nije pronađena: " + retailerCode
                ));

        if (retailer.datasetUrl() == null
                || retailer.datasetUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "Prodavnica nema podešen dataset URL: "
                            + retailerCode
            );
        }

        Long importRunId = startImport(
                retailer.id(),
                retailer.datasetUrl()
        );

        LatestPriceSnapshot latestSnapshot =
                new LatestPriceSnapshot();

        int rowsRead = 0;
        int rowsSaved = 0;
        int rowsWithErrors = 0;
        int parseErrorsLogged = 0;

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
                        "Preuzimanje CSV fajla nije uspelo. "
                                + "HTTP status: "
                                + response.statusCode()
                );
            }

            /*
             * Ceo fajl mora da se pročita da bismo pronašli
             * stvarno najnoviji datum cenovnika.
             *
             * Parametar maxRows privremeno ostaje u potpisu metode
             * zbog postojećeg kontrolera, ali se ovde više ne koristi
             * za prekid čitanja.
             */
            try (
                    InputStream inputStream = response.body();
                    Reader reader = createBomAwareReader(inputStream);
                    CSVParser parser = CSV_FORMAT.parse(reader)
            ) {
                for (CSVRecord record : parser) {
                    rowsRead++;

                    try {
                        PriceCsvRow row = parseRecord(record);

                        if (row != null) {
                            latestSnapshot.accept(row);
                        }
                    } catch (RuntimeException exception) {
                        rowsWithErrors++;

                        if (parseErrorsLogged < MAX_DETAILED_PARSE_ERROR_LOGS) {
                            log.warn(
                                    "Preskočen CSV red {}: {}",
                                    record.getRecordNumber(),
                                    exception.getMessage()
                            );

                            parseErrorsLogged++;
                        } else if (
                                parseErrorsLogged
                                        == MAX_DETAILED_PARSE_ERROR_LOGS
                        ) {
                            log.warn(
                                    "Dostignut limit od {} detaljnih CSV grešaka. "
                                            + "Preostale greške biće samo prebrojane.",
                                    MAX_DETAILED_PARSE_ERROR_LOGS
                            );

                            parseErrorsLogged++;
                        }
                    }
                }
            }

            log.info(
                    "CSV čitanje završeno: rowsRead={}, "
                            + "snapshotDate={}, rowsSelected={}, "
                            + "parseErrors={}",
                    rowsRead,
                    latestSnapshot.snapshotDate(),
                    latestSnapshot.rowsSelected(),
                    rowsWithErrors
            );

            if (latestSnapshot.snapshotDate() == null) {
                throw new IllegalStateException(
                        "CSV ne sadrži nijedan ispravan red sa cenom."
                );
            }

            int rowsSelected = latestSnapshot.rowsSelected();

            BatchWriteResult writeResult = saveSnapshotInBatches(
                    retailer.id(),
                    importRunId,
                    latestSnapshot.rows()
            );

            rowsSaved = writeResult.rowsSaved();
            rowsWithErrors += writeResult.rowsWithErrors();

            int rowsSkipped = Math.max(
                    rowsRead - rowsSaved,
                    0
            );

            String status = rowsWithErrors == 0
                    ? "SUCCEEDED"
                    : "SUCCEEDED_WITH_ERRORS";

            completeImport(
                    importRunId,
                    latestSnapshot.snapshotDate(),
                    rowsRead,
                    rowsSelected,
                    rowsSaved,
                    rowsSkipped,
                    status
            );

            return new ImportResult(
                    importRunId,
                    latestSnapshot.snapshotDate(),
                    rowsRead,
                    rowsSelected,
                    rowsSaved,
                    rowsSkipped,
                    status
            );
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            int rowsSelected = latestSnapshot.rowsSelected();

            int rowsSkipped = Math.max(
                    rowsRead - rowsSaved,
                    0
            );

            failImport(
                    importRunId,
                    latestSnapshot.snapshotDate(),
                    rowsRead,
                    rowsSelected,
                    rowsSaved,
                    rowsSkipped,
                    exception.getMessage()
            );

            throw new IllegalStateException(
                    "Import cena nije uspeo: "
                            + exception.getMessage(),
                    exception
            );
        }
    }

    private BatchWriteResult saveSnapshotInBatches(
            Long retailerId,
            Long importRunId,
            List<PriceCsvRow> rows
    ) {
        int rowsSaved = 0;
        int rowsWithErrors = 0;

        for (
                int batchStart = 0;
                batchStart < rows.size();
                batchStart += WRITE_BATCH_SIZE
        ) {
            int batchEnd = Math.min(
                    batchStart + WRITE_BATCH_SIZE,
                    rows.size()
            );

            List<PriceCsvRow> batch = rows.subList(
                    batchStart,
                    batchEnd
            );

            int batchNumber =
                    batchStart / WRITE_BATCH_SIZE + 1;

            try {
                /*
                 * Svi redovi paketa se čuvaju u istoj transakciji.
                 * Ako jedan padne, ceo paket se vraća.
                 */
                transactionTemplate.executeWithoutResult(
                        transactionStatus -> {
                            for (PriceCsvRow row : batch) {
                                saveRecord(
                                        retailerId,
                                        importRunId,
                                        row
                                );
                            }
                        }
                );

                rowsSaved += batch.size();

                log.info(
                        "Sačuvan paket {}: {} redova.",
                        batchNumber,
                        batch.size()
                );
            } catch (RuntimeException batchException) {
                log.warn(
                        "Paket {} sa {} redova nije sačuvan: {}. "
                                + "Pokušavam red po red.",
                        batchNumber,
                        batch.size(),
                        batchException.getMessage()
                );

                /*
                 * Prethodna transakcija je vraćena, zato sada
                 * svaki red dobija zasebnu transakciju.
                 */
                for (PriceCsvRow row : batch) {
                    try {
                        transactionTemplate.executeWithoutResult(
                                transactionStatus -> saveRecord(
                                        retailerId,
                                        importRunId,
                                        row
                                )
                        );

                        rowsSaved++;
                    } catch (RuntimeException rowException) {
                        rowsWithErrors++;

                        log.warn(
                                "Nije sačuvan proizvod {} "
                                        + "iz formata '{}': {}",
                                row.sourceProductKey(),
                                row.retailerFormatName(),
                                rowException.getMessage()
                        );
                    }
                }
            }
        }

        return new BatchWriteResult(
                rowsSaved,
                rowsWithErrors
        );
    }

    protected void saveRecord(
            Long retailerId,
            Long importRunId,
            PriceCsvRow row
    ) {
        Long canonicalProductId = exactEanMatcher.matchOrCreate(
                row.barcode(),
                row.productName(),
                row.normalizedProductName(),
                row.brand(),
                row.quantityValue(),
                row.baseUnit()
        ).orElse(null);

        Long retailerProductId = upsertProduct(
                retailerId,
                row.sourceProductKey(),
                row.categoryCode(),
                row.categoryName(),
                row.productName(),
                row.normalizedProductName(),
                row.brand(),
                row.barcode(),
                row.unitOfMeasure(),
                row.quantityValue(),
                row.baseUnit(),
                canonicalProductId
        );

        insertPriceObservation(
                retailerProductId,
                importRunId,
                row.retailerFormatName(),
                row.priceDate(),
                row.regularPrice(),
                row.unitPrice(),
                row.discountedPrice(),
                row.discountStartDate(),
                row.discountEndDate(),
                row.vatRate()
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
            String sourceProductKey,
            String categoryCode,
            String categoryName,
            String productName,
            String normalizedProductName,
            String brand,
            String barcode,
            String unit,
            BigDecimal quantityValue,
            String baseUnit,
            Long canonicalProductId
    ) {
        return jdbcClient.sql("""
                INSERT INTO app.retailer_product (
                    retailer_id,
                    source_product_key,
                    category_code,
                    category_name,
                    name,
                    normalized_name,
                    brand,
                    barcode,
                    unit,
                    quantity_value,
                    base_unit,
                    canonical_product_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    retailer_id,
                    source_product_key
                )
                DO UPDATE SET
                    category_code = EXCLUDED.category_code,
                    category_name = EXCLUDED.category_name,
                    name = EXCLUDED.name,
                    normalized_name = EXCLUDED.normalized_name,
                    brand = EXCLUDED.brand,
                    barcode = EXCLUDED.barcode,
                    unit = EXCLUDED.unit,
                    quantity_value = EXCLUDED.quantity_value,
                    base_unit = EXCLUDED.base_unit,
                    canonical_product_id = EXCLUDED.canonical_product_id
                RETURNING id
                """)
                .param(1, retailerId)
                .param(2, sourceProductKey)
                .param(
                        3,
                        nullableText(categoryCode),
                        Types.VARCHAR
                )
                .param(
                        4,
                        nullableText(categoryName),
                        Types.VARCHAR
                )
                .param(5, productName)
                .param(6, normalizedProductName)
                .param(
                        7,
                        nullableText(brand),
                        Types.VARCHAR
                )
                .param(
                        8,
                        barcode,
                        Types.VARCHAR
                )
                .param(
                        9,
                        nullableText(unit),
                        Types.VARCHAR
                )
                .param(10, quantityValue, Types.NUMERIC)
                .param(11, baseUnit, Types.VARCHAR)
                .param(12, canonicalProductId, Types.BIGINT)
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
                        retailer_format_name,
                        store_id
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
            LocalDate snapshotDate,
            int rowsRead,
            int rowsSelected,
            int rowsSaved,
            int rowsSkipped,
            String status
    ) {
        jdbcClient.sql("""
                    UPDATE app.import_run
                    SET status = ?,
                        finished_at = NOW(),
                        snapshot_date = ?,
                        rows_read = ?,
                        rows_selected = ?,
                        rows_saved = ?,
                        rows_skipped = ?,
                        error_message = NULL
                    WHERE id = ?
                    """)
                .param(1, status)
                .param(2, snapshotDate, Types.DATE)
                .param(3, rowsRead)
                .param(4, rowsSelected)
                .param(5, rowsSaved)
                .param(6, rowsSkipped)
                .param(7, importRunId)
                .update();
    }

    private void failImport(
            Long importRunId,
            LocalDate snapshotDate,
            int rowsRead,
            int rowsSelected,
            int rowsSaved,
            int rowsSkipped,
            String errorMessage
    ) {
        jdbcClient.sql("""
                    UPDATE app.import_run
                    SET status = 'FAILED',
                        finished_at = NOW(),
                        snapshot_date = ?,
                        rows_read = ?,
                        rows_selected = ?,
                        rows_saved = ?,
                        rows_skipped = ?,
                        error_message = ?
                    WHERE id = ?
                    """)
                .param(
                        1,
                        snapshotDate,
                        Types.DATE
                )
                .param(2, rowsRead)
                .param(3, rowsSelected)
                .param(4, rowsSaved)
                .param(5, rowsSkipped)
                .param(
                        6,
                        shortenErrorMessage(errorMessage),
                        Types.VARCHAR
                )
                .param(7, importRunId)
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

    private PriceCsvRow parseRecord(CSVRecord record) {
        String categoryCode = normalizeTextValue(
                record.get("KATEGORIJA")
        );

        String categoryName = normalizeTextValue(
                record.get("NAZIV KATEGORIJE")
        );

        String productName = requireText(
                record.get("Naziv proizvoda"),
                "Naziv proizvoda"
        );

        String normalizedProductName =
                productNameNormalizer.normalize(productName);

        ParsedQuantity parsedQuantity =
                productQuantityParser.parse(productName)
                        .orElse(null);

        BigDecimal quantityValue = parsedQuantity == null
                ? null
                : parsedQuantity.value();

        String baseUnit = parsedQuantity == null
                ? null
                : parsedQuantity.unit().databaseValue();

        String brand = normalizeTextValue(
                record.get("Robna marka")
        );

        String barcode = normalizeBarcode(
                record.get("Barkod proizvoda")
        );

        String unitOfMeasure = normalizeTextValue(
                record.get("Jedinica mere")
        );

        String retailerFormatName = requireText(
                record.get("Naziv trgovca - formata*"),
                "Naziv trgovca - formata*"
        );

        BigDecimal regularPrice = parseDecimal(
                record.get("Redovna cena")
        );

        BigDecimal discountedPrice = parseDecimal(
                record.get("Snižena cena")
        );

        /*
         * Red bez redovne i bez snižene cene nije upotrebljiv
         * za poređenje cena.
         */
        if (regularPrice == null && discountedPrice == null) {
            return null;
        }

        LocalDate priceDate = parseRequiredDate(
                record.get("Datum cenovnika")
        );

        BigDecimal unitPrice = parseDecimal(
                record.get("Cena po jedinici mere")
        );

        LocalDate discountStartDate = parseDate(
                record.get("Datum početka sniženja")
        );

        LocalDate discountEndDate = parseDate(
                record.get("Datum kraja sniženja")
        );

        BigDecimal vatRate = parseDecimal(
                record.get("Stopa PDV")
        );

        String sourceProductKey = createSourceProductKey(
                barcode,
                productName,
                brand,
                unitOfMeasure,
                categoryCode
        );

        return new PriceCsvRow(
                sourceProductKey,
                categoryCode,
                categoryName,
                productName,
                normalizedProductName,
                brand,
                barcode,
                unitOfMeasure,
                quantityValue,
                baseUnit,
                retailerFormatName,
                priceDate,
                regularPrice,
                unitPrice,
                discountedPrice,
                discountStartDate,
                discountEndDate,
                vatRate
        );
    }

    private String normalizeBarcode(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.strip();

        boolean validLengthAndDigits =
                normalized.matches("[0-9]{8,14}");

        boolean containsOnlyZeros =
                !normalized.isEmpty()
                        && normalized.chars()
                        .allMatch(character -> character == '0');

        if (!validLengthAndDigits || containsOnlyZeros) {
            return null;
        }

        return normalized;
    }

    private String createSourceProductKey(
            String barcode,
            String productName,
            String brand,
            String unitOfMeasure,
            String categoryCode
    ) {
        if (barcode != null) {
            return "BARCODE:" + barcode;
        }

        String fingerprintInput = String.join(
                "|",
                normalizeFingerprintPart(productName),
                normalizeFingerprintPart(brand),
                normalizeFingerprintPart(unitOfMeasure),
                normalizeFingerprintPart(categoryCode)
        );

        return "FINGERPRINT:" + sha256Hex(fingerprintInput);
    }

    private String normalizeFingerprintPart(String value) {
        String normalized = normalizeTextValue(value);

        if (normalized == null) {
            return "";
        }

        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeTextValue(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value
                .strip()
                .replaceAll("\\s+", " ");

        return normalized.isEmpty() ? null : normalized;
    }

    private String requireText(
            String value,
            String columnName
    ) {
        String normalized = normalizeTextValue(value);

        if (normalized == null) {
            throw new IllegalArgumentException(
                    "CSV kolona '" + columnName + "' je prazna."
            );
        }

        return normalized;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest messageDigest =
                    MessageDigest.getInstance("SHA-256");

            byte[] hash = messageDigest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algoritam nije dostupan.",
                    exception
            );
        }
    }

    private record BatchWriteResult(
            int rowsSaved,
            int rowsWithErrors
    ) {
    }
}
