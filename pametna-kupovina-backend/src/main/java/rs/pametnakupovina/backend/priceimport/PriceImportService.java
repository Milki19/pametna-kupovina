package rs.pametnakupovina.backend.priceimport;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.matching.ExactEanMatcher;
import rs.pametnakupovina.backend.matching.ParsedQuantity;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
import rs.pametnakupovina.backend.product.ProductCatalogMaintenanceService;
import rs.pametnakupovina.backend.retailer.Retailer;
import rs.pametnakupovina.backend.retailer.RetailerRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
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
    private final RetailerDataSourceRepository dataSourceRepository;
    private final GovernmentDataResourceDiscoveryClient discoveryClient;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductQuantityParser productQuantityParser;
    private final ExactEanMatcher exactEanMatcher;
    private final ProductCatalogMaintenanceService catalogMaintenanceService;
    private final HttpClient httpClient;
    private final TransactionTemplate transactionTemplate;
    private final Duration requestTimeout;
    private final long maxDownloadBytes;
    private final Path archiveDirectory;

    public PriceImportService(
            JdbcClient jdbcClient,
            RetailerRepository retailerRepository,
            RetailerDataSourceRepository dataSourceRepository,
            GovernmentDataResourceDiscoveryClient discoveryClient,
            ProductNameNormalizer productNameNormalizer,
            ProductQuantityParser productQuantityParser,
            ExactEanMatcher exactEanMatcher,
            ProductCatalogMaintenanceService catalogMaintenanceService,
            PlatformTransactionManager transactionManager,
            @Value("${price-import.http.connect-timeout-seconds:20}")
            long connectTimeoutSeconds,
            @Value("${price-import.http.request-timeout-seconds:900}")
            long requestTimeoutSeconds,
            @Value("${price-import.max-download-bytes:1500000000}")
            long maxDownloadBytes,
            @Value("${price-import.archive.directory:}")
            String archiveDirectory
    ) {
        this.jdbcClient = jdbcClient;
        this.retailerRepository = retailerRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.discoveryClient = discoveryClient;
        this.productNameNormalizer = productNameNormalizer;
        this.productQuantityParser = productQuantityParser;
        this.exactEanMatcher = exactEanMatcher;
        this.catalogMaintenanceService = catalogMaintenanceService;
        this.transactionTemplate =
                new TransactionTemplate(transactionManager);
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.maxDownloadBytes = maxDownloadBytes;
        this.archiveDirectory = archiveDirectory == null
                || archiveDirectory.isBlank()
                ? null
                : Path.of(archiveDirectory).toAbsolutePath().normalize();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Kompatibilni ulaz za starije pozive. Ceo fajl mora da se pročita da bi
     * se pronašao stvarno najnoviji datum, pa maxRows više nije bezbedna
     * semantika za ovaj importer.
     */
    public ImportResult importPrices(String retailerCode, int maxRows) {
        return importPrices(retailerCode);
    }

    public ImportResult importPrices(String retailerCode) {
        Retailer retailer = retailerRepository.findByCode(retailerCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prodavnica nije pronađena: " + retailerCode
                ));

        RetailerDataSourceRepository.PriceSource priceSource =
                dataSourceRepository.resolveCatalog(
                        retailer.id(),
                        retailer.datasetUrl(),
                        false
                );

        String resolvedSourceUrl = resolveLatestSourceUrl(priceSource);

        if (resolvedSourceUrl == null || resolvedSourceUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Prodavnica nema podešen dataset URL: "
                            + retailerCode
            );
        }

        Long importRunId = startImport(
                retailer.id(),
                priceSource.id(),
                resolvedSourceUrl
        );

        int rowsRead = 0;
        int rowsSelected = 0;
        int rowsSaved = 0;
        int rowsWithErrors = 0;
        LocalDate snapshotDate = null;
        Path downloadedFile = null;

        try {
            DownloadedCsv downloadedCsv = downloadCsv(
                    resolvedSourceUrl
            );

            downloadedFile = downloadedCsv.path();
            updateImportChecksum(importRunId, downloadedCsv.checksum());

            SnapshotScanResult scanResult = scanLatestSnapshot(
                    downloadedFile
            );

            rowsRead = scanResult.rowsRead();
            snapshotDate = scanResult.snapshotDate();
            rowsWithErrors = scanResult.rowsWithErrors();

            archiveDownloadedCsv(
                    retailer.code(),
                    snapshotDate,
                    downloadedCsv
            );

            log.info(
                    "CSV čitanje završeno: rowsRead={}, "
                            + "snapshotDate={}, scanErrors={}",
                    rowsRead,
                    snapshotDate,
                    rowsWithErrors
            );

            if (snapshotDate == null) {
                throw new IllegalStateException(
                        "CSV ne sadrži nijedan ispravan red sa cenom."
                );
            }

            SnapshotWriteResult writeResult = importLatestSnapshot(
                    retailer.id(),
                    importRunId,
                    downloadedFile,
                    scanResult
            );

            rowsSelected = writeResult.rowsSelected();
            rowsSaved = writeResult.rowsSaved();
            rowsWithErrors += writeResult.rowsWithErrors();

            int rowsSkipped = Math.max(
                    rowsRead - rowsSaved,
                    0
            );

            String status = rowsWithErrors == 0
                    ? "SUCCEEDED"
                    : "SUCCEEDED_WITH_ERRORS";

            catalogMaintenanceService.refreshRetailer(retailer.id());

            completeImport(
                    importRunId,
                    snapshotDate,
                    rowsRead,
                    rowsSelected,
                    rowsSaved,
                    rowsSkipped,
                    status
            );

            return new ImportResult(
                    importRunId,
                    snapshotDate,
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

            int rowsSkipped = Math.max(
                    rowsRead - rowsSaved,
                    0
            );

            failImport(
                    importRunId,
                    snapshotDate,
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
        } finally {
            if (downloadedFile != null) {
                try {
                    Files.deleteIfExists(downloadedFile);
                } catch (IOException exception) {
                    log.warn(
                            "Privremeni CSV fajl nije obrisan: {}",
                            downloadedFile,
                            exception
                    );
                }
            }
        }
    }

    private String resolveLatestSourceUrl(
            RetailerDataSourceRepository.PriceSource priceSource
    ) {
        boolean discoverableGovernmentCatalog =
                "GOV_RS_SEMICOLON_CSV".equals(
                        priceSource.parserProfile()
                ) || "PRAVILNIK_76_2026_CSV".equals(
                        priceSource.parserProfile()
                );

        if (!discoverableGovernmentCatalog
                || priceSource.discoveryUrl() == null
                || priceSource.discoveryUrl().isBlank()) {
            return priceSource.sourceUrl();
        }

        try {
            String discoveredUrl = discoveryClient.discoverLatestCsv(
                    priceSource.discoveryUrl()
            ).url();

            if (!discoveredUrl.equals(priceSource.sourceUrl())) {
                dataSourceRepository.updateResolvedSourceUrl(
                        priceSource.id(),
                        discoveredUrl
                );
                log.info(
                        "Otkriven najnoviji data.gov.rs CSV: {}",
                        discoveredUrl
                );
            }

            return discoveredUrl;
        } catch (RuntimeException exception) {
            log.warn(
                    "Otkrivanje najnovijeg data.gov.rs resursa nije "
                            + "uspelo; koristi se poslednji poznati URL: {}",
                    priceSource.sourceUrl(),
                    exception
            );
            return priceSource.sourceUrl();
        }
    }

    public ImportResult importStorePrices(
            String retailerCode,
            String storeExternalCode,
            String sourceUrl,
            LocalDate snapshotDate
    ) {
        StorePriceTarget target = findStorePriceTarget(
                retailerCode,
                storeExternalCode
        );

        RetailerDataSourceRepository.PriceSource priceSource =
                dataSourceRepository.resolveCatalog(
                        target.retailerId(),
                        sourceUrl,
                        true
                );

        Long importRunId = startImport(
                target.retailerId(),
                priceSource.id(),
                sourceUrl
        );

        int rowsRead = 0;
        int rowsSelected = 0;
        int rowsSaved = 0;
        int rowsWithErrors = 0;
        Path downloadedFile = null;

        try {
            DownloadedCsv downloadedCsv = downloadCsv(sourceUrl);
            downloadedFile = downloadedCsv.path();
            updateImportChecksum(importRunId, downloadedCsv.checksum());
            archiveDownloadedCsv(
                    retailerCode + "-" + storeExternalCode,
                    snapshotDate,
                    downloadedCsv
            );

            StoreSnapshotWriteResult writeResult = importStoreSnapshot(
                    target,
                    importRunId,
                    downloadedFile,
                    snapshotDate
            );

            rowsRead = writeResult.rowsRead();
            rowsSelected = writeResult.rowsSelected();
            rowsSaved = writeResult.rowsSaved();
            rowsWithErrors = writeResult.rowsWithErrors();

            int rowsSkipped = Math.max(rowsRead - rowsSaved, 0);
            String status = rowsWithErrors == 0
                    ? "SUCCEEDED"
                    : "SUCCEEDED_WITH_ERRORS";

            catalogMaintenanceService.refreshRetailer(
                    target.retailerId()
            );

            completeImport(
                    importRunId,
                    snapshotDate,
                    rowsRead,
                    rowsSelected,
                    rowsSaved,
                    rowsSkipped,
                    status
            );

            return new ImportResult(
                    importRunId,
                    snapshotDate,
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

            int rowsSkipped = Math.max(rowsRead - rowsSaved, 0);

            failImport(
                    importRunId,
                    snapshotDate,
                    rowsRead,
                    rowsSelected,
                    rowsSaved,
                    rowsSkipped,
                    exception.getMessage()
            );

            throw new IllegalStateException(
                    "Import cena za prodavnicu nije uspeo: "
                            + exception.getMessage(),
                    exception
            );
        } finally {
            if (downloadedFile != null) {
                try {
                    Files.deleteIfExists(downloadedFile);
                } catch (IOException exception) {
                    log.warn(
                            "Privremeni store CSV fajl nije obrisan: {}",
                            downloadedFile,
                            exception
                    );
                }
            }
        }
    }

    private StorePriceTarget findStorePriceTarget(
            String retailerCode,
            String storeExternalCode
    ) {
        return jdbcClient.sql("""
                    SELECT retailer.id AS retailer_id,
                           store.id AS store_id,
                           format.name AS format_name
                    FROM app.store AS store
                    JOIN app.retailer AS retailer
                      ON retailer.id = store.retailer_id
                    JOIN app.store_format AS format
                      ON format.id = store.store_format_id
                     AND format.retailer_id = store.retailer_id
                    WHERE UPPER(retailer.code) = UPPER(?)
                      AND UPPER(store.external_code) = UPPER(?)
                      AND store.active = TRUE
                      AND format.active = TRUE
                    """)
                .param(1, retailerCode)
                .param(2, storeExternalCode)
                .query((resultSet, rowNumber) -> new StorePriceTarget(
                        resultSet.getLong("retailer_id"),
                        resultSet.getLong("store_id"),
                        resultSet.getString("format_name")
                ))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Aktivna prodavnica nije pronađena: "
                                + retailerCode
                                + "/"
                                + storeExternalCode
                ));
    }

    private StoreSnapshotWriteResult importStoreSnapshot(
            StorePriceTarget target,
            Long importRunId,
            Path csvPath,
            LocalDate snapshotDate
    ) throws IOException {
        int rowsRead = 0;
        int rowsSelected = 0;
        int rowsSaved = 0;
        int rowsWithErrors = 0;
        int errorsLogged = 0;
        List<PriceCsvRow> batch = new java.util.ArrayList<>(
                WRITE_BATCH_SIZE
        );

        try (
                InputStream inputStream = Files.newInputStream(csvPath);
                Reader reader = createBomAwareReader(inputStream);
                CSVParser parser = CSV_FORMAT.parse(reader)
        ) {
            for (CSVRecord record : parser) {
                rowsRead++;

                try {
                    PriceCsvRow row = parseStorePriceRecord(
                            record,
                            target,
                            snapshotDate
                    );

                    if (row == null) {
                        continue;
                    }

                    rowsSelected++;
                    batch.add(row);

                    if (batch.size() == WRITE_BATCH_SIZE) {
                        BatchWriteResult result = saveSnapshotInBatches(
                                target.retailerId(),
                                importRunId,
                                batch
                        );
                        rowsSaved += result.rowsSaved();
                        rowsWithErrors += result.rowsWithErrors();
                        batch.clear();
                    }
                } catch (RuntimeException exception) {
                    rowsWithErrors++;
                    errorsLogged = logParseError(
                            record,
                            exception,
                            errorsLogged
                    );
                }
            }
        }

        if (!batch.isEmpty()) {
            BatchWriteResult result = saveSnapshotInBatches(
                    target.retailerId(),
                    importRunId,
                    batch
            );
            rowsSaved += result.rowsSaved();
            rowsWithErrors += result.rowsWithErrors();
        }

        return new StoreSnapshotWriteResult(
                rowsRead,
                rowsSelected,
                rowsSaved,
                rowsWithErrors
        );
    }

    private PriceCsvRow parseStorePriceRecord(
            CSVRecord record,
            StorePriceTarget target,
            LocalDate snapshotDate
    ) {
        String productName = requireText(
                column(record, "NAZIV PROIZVODA"),
                "NAZIV PROIZVODA"
        );
        String normalizedProductName =
                productNameNormalizer.normalize(productName);
        String barcode = normalizeBarcode(
                column(record, "BARKOD PROIZVODA")
        );
        BigDecimal regularPrice = positiveOrNull(
                parseCurrencyAmount(column(record, "REDOVNA CENA"))
        );
        BigDecimal discountedPrice = positiveOrNull(
                parseCurrencyAmount(
                        column(record, "SNIZENA CENA", "SNIŽENA CENA")
                )
        );

        if (regularPrice == null && discountedPrice == null) {
            return null;
        }

        String unitPriceText = column(
                record,
                "CENA PO JEDINICI MERE"
        );
        BigDecimal unitPrice = parseCurrencyAmount(unitPriceText);
        String unitOfMeasure = parsePriceUnit(unitPriceText);
        ParsedQuantity parsedQuantity =
                productQuantityParser.parse(productName)
                        .orElse(null);
        BigDecimal quantityValue = parsedQuantity == null
                ? null
                : parsedQuantity.value();
        String baseUnit = parsedQuantity == null
                ? null
                : parsedQuantity.unit().databaseValue();
        String sourceProductKey = createSourceProductKey(
                barcode,
                productName,
                null,
                unitOfMeasure,
                null
        );

        return new PriceCsvRow(
                sourceProductKey,
                null,
                null,
                productName,
                normalizedProductName,
                null,
                barcode,
                unitOfMeasure,
                quantityValue,
                baseUnit,
                target.formatName(),
                snapshotDate,
                regularPrice,
                unitPrice,
                discountedPrice,
                null,
                null,
                null,
                target.storeId()
        );
    }

    private BigDecimal parseCurrencyAmount(String value) {
        String normalized = nullableText(value);

        if (normalized == null) {
            return null;
        }

        int currencyIndex = normalized.toLowerCase(Locale.ROOT)
                .indexOf("rsd");

        String numericPart = currencyIndex < 0
                ? normalized
                : normalized.substring(0, currencyIndex);

        return parseDecimal(numericPart);
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private String parsePriceUnit(String value) {
        String normalized = nullableText(value);

        if (normalized == null) {
            return null;
        }

        int separatorIndex = normalized.indexOf('/');

        if (separatorIndex < 0 || separatorIndex == normalized.length() - 1) {
            return null;
        }

        return normalizeTextValue(
                normalized.substring(separatorIndex + 1)
        );
    }

    private DownloadedCsv downloadCsv(String sourceUrl)
            throws IOException, InterruptedException {
        URI sourceUri = validateSourceUri(sourceUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(sourceUri)
                .timeout(requestTimeout)
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

        long advertisedSize = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L);

        if (advertisedSize > maxDownloadBytes) {
            response.body().close();

            throw new IllegalStateException(
                    "CSV fajl je veći od dozvoljenog limita od "
                            + maxDownloadBytes
                            + " bajtova."
            );
        }

        Path temporaryFile = Files.createTempFile(
                "pametna-kupovina-price-import-",
                ".csv"
        );

        MessageDigest digest = newSha256Digest();
        long downloadedBytes = 0;

        try (
                InputStream inputStream = response.body();
                OutputStream outputStream = Files.newOutputStream(
                        temporaryFile
                )
        ) {
            byte[] buffer = new byte[64 * 1024];
            int bytesRead;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                downloadedBytes += bytesRead;

                if (downloadedBytes > maxDownloadBytes) {
                    throw new IllegalStateException(
                            "CSV fajl je tokom preuzimanja prešao limit od "
                                    + maxDownloadBytes
                                    + " bajtova."
                    );
                }

                digest.update(buffer, 0, bytesRead);
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporaryFile);
            throw exception;
        }

        if (downloadedBytes == 0) {
            Files.deleteIfExists(temporaryFile);
            throw new IllegalStateException("Preuzeti CSV fajl je prazan.");
        }

        log.info(
                "Preuzet CSV: source={}, bytes={}",
                sourceUri,
                downloadedBytes
        );

        return new DownloadedCsv(
                temporaryFile,
                HexFormat.of().formatHex(digest.digest())
        );
    }

    private void archiveDownloadedCsv(
            String sourceCode,
            LocalDate snapshotDate,
            DownloadedCsv downloadedCsv
    ) throws IOException {
        if (archiveDirectory == null) {
            return;
        }

        String safeSourceCode = sourceCode
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]", "_");
        String safeDate = snapshotDate == null
                ? "unknown-date"
                : snapshotDate.toString();
        Path sourceDirectory = archiveDirectory
                .resolve(safeSourceCode)
                .normalize();

        if (!sourceDirectory.startsWith(archiveDirectory)) {
            throw new IllegalStateException(
                    "Neispravna putanja arhive za izvor: " + sourceCode
            );
        }

        Files.createDirectories(sourceDirectory);
        Path archivePath = sourceDirectory.resolve(
                safeDate + "-" + downloadedCsv.checksum() + ".csv"
        );

        try {
            Files.copy(downloadedCsv.path(), archivePath);
            log.info("Arhiviran izvorni CSV: {}", archivePath);
        } catch (FileAlreadyExistsException ignored) {
            log.info("Izvorni CSV je već arhiviran: {}", archivePath);
        }
    }

    private URI validateSourceUri(String sourceUrl) {
        URI uri = URI.create(sourceUrl);
        String scheme = uri.getScheme();
        String host = uri.getHost();

        boolean secureRemoteSource = "https".equalsIgnoreCase(scheme)
                && host != null;

        boolean localTestSource = "http".equalsIgnoreCase(scheme)
                && host != null
                && (
                host.equalsIgnoreCase("localhost")
                        || host.equals("127.0.0.1")
                        || host.equals("::1")
        );

        if (!secureRemoteSource && !localTestSource) {
            throw new IllegalArgumentException(
                    "Dataset URL mora koristiti HTTPS; HTTP je dozvoljen "
                            + "samo za lokalno testiranje."
            );
        }

        return uri;
    }

    private SnapshotScanResult scanLatestSnapshot(Path csvPath)
            throws IOException {
        int rowsRead = 0;
        int rowsWithErrors = 0;
        int errorsLogged = 0;
        LocalDate latestAnyDate = null;
        LocalDate latestCurrentDate = null;

        try (
                InputStream inputStream = Files.newInputStream(csvPath);
                Reader reader = createBomAwareReader(inputStream);
                CSVParser parser = CSV_FORMAT.parse(reader)
        ) {
            for (CSVRecord record : parser) {
                rowsRead++;

                try {
                    BigDecimal regularPrice = positiveOrNull(
                            parseDecimal(column(record, "Redovna cena"))
                    );
                    BigDecimal discountedPrice = positiveOrNull(
                            parseDecimal(optionalColumn(
                                    record,
                                    "Snižena cena",
                                    "Snizena cena"
                            ))
                    );

                    if (regularPrice == null && discountedPrice == null) {
                        continue;
                    }

                    LocalDate rowDate = parseRequiredDate(
                            column(record, "Datum cenovnika")
                    );

                    if (latestAnyDate == null
                            || rowDate.isAfter(latestAnyDate)) {
                        latestAnyDate = rowDate;
                    }

                    if (!isMonthlySnapshot(record)
                            && (latestCurrentDate == null
                            || rowDate.isAfter(latestCurrentDate))) {
                        latestCurrentDate = rowDate;
                    }
                } catch (RuntimeException exception) {
                    rowsWithErrors++;
                    errorsLogged = logParseError(
                            record,
                            exception,
                            errorsLogged
                    );
                }
            }
        }

        boolean excludeMonthlySnapshots = latestCurrentDate != null;

        return new SnapshotScanResult(
                rowsRead,
                rowsWithErrors,
                excludeMonthlySnapshots
                        ? latestCurrentDate
                        : latestAnyDate,
                excludeMonthlySnapshots
        );
    }

    private SnapshotWriteResult importLatestSnapshot(
            Long retailerId,
            Long importRunId,
            Path csvPath,
            SnapshotScanResult scanResult
    ) throws IOException {
        int rowsSelected = 0;
        int rowsSaved = 0;
        int rowsWithErrors = 0;
        int errorsLogged = 0;
        List<PriceCsvRow> batch = new java.util.ArrayList<>(
                WRITE_BATCH_SIZE
        );

        try (
                InputStream inputStream = Files.newInputStream(csvPath);
                Reader reader = createBomAwareReader(inputStream);
                CSVParser parser = CSV_FORMAT.parse(reader)
        ) {
            for (CSVRecord record : parser) {
                try {
                    LocalDate rowDate = parseRequiredDate(
                            column(record, "Datum cenovnika")
                    );

                    if (!scanResult.snapshotDate().equals(rowDate)
                            || (scanResult.excludeMonthlySnapshots()
                            && isMonthlySnapshot(record))) {
                        continue;
                    }

                    PriceCsvRow row = parseRecord(record);

                    if (row == null) {
                        continue;
                    }

                    rowsSelected++;
                    batch.add(row);

                    if (batch.size() == WRITE_BATCH_SIZE) {
                        BatchWriteResult result = saveSnapshotInBatches(
                                retailerId,
                                importRunId,
                                batch
                        );

                        rowsSaved += result.rowsSaved();
                        rowsWithErrors += result.rowsWithErrors();
                        batch.clear();
                    }
                } catch (RuntimeException exception) {
                    rowsWithErrors++;
                    errorsLogged = logParseError(
                            record,
                            exception,
                            errorsLogged
                    );
                }
            }
        }

        if (!batch.isEmpty()) {
            BatchWriteResult result = saveSnapshotInBatches(
                    retailerId,
                    importRunId,
                    batch
            );

            rowsSaved += result.rowsSaved();
            rowsWithErrors += result.rowsWithErrors();
        }

        return new SnapshotWriteResult(
                rowsSelected,
                rowsSaved,
                rowsWithErrors
        );
    }

    private int logParseError(
            CSVRecord record,
            RuntimeException exception,
            int errorsLogged
    ) {
        if (errorsLogged < MAX_DETAILED_PARSE_ERROR_LOGS) {
            log.warn(
                    "Preskočen CSV red {}: {}",
                    record.getRecordNumber(),
                    exception.getMessage()
            );

            return errorsLogged + 1;
        }

        if (errorsLogged == MAX_DETAILED_PARSE_ERROR_LOGS) {
            log.warn(
                    "Dostignut limit od {} detaljnih CSV grešaka. "
                            + "Preostale greške biće samo prebrojane.",
                    MAX_DETAILED_PARSE_ERROR_LOGS
            );

            return errorsLogged + 1;
        }

        return errorsLogged;
    }

    private void updateImportChecksum(Long importRunId, String checksum) {
        jdbcClient.sql("""
                    UPDATE app.import_run
                    SET checksum = ?
                    WHERE id = ?
                    """)
                .param(1, checksum)
                .param(2, importRunId)
                .update();
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

        assignControlledCategory(retailerProductId);

        savePriceSnapshot(
                retailerProductId,
                importRunId,
                row.retailerFormatName(),
                row.storeId(),
                row.priceDate(),
                row.regularPrice(),
                row.unitPrice(),
                row.discountedPrice(),
                row.discountStartDate(),
                row.discountEndDate(),
                row.vatRate()
        );
    }

    private Long startImport(
            Long retailerId,
            Long dataSourceId,
            String sourceUrl
    ) {
        if (!dataSourceRepository.tryMarkRunning(dataSourceId)) {
            throw new IllegalStateException(
                    "Import za ovaj izvor je već u toku."
            );
        }

        try {
            return jdbcClient.sql("""
                            INSERT INTO app.import_run (
                                retailer_id,
                                data_source_id,
                                source_url,
                                status
                            )
                            VALUES (?, ?, ?, 'RUNNING')
                            RETURNING id
                            """)
                    .param(1, retailerId)
                    .param(2, dataSourceId, Types.BIGINT)
                    .param(3, sourceUrl)
                    .query(Long.class)
                    .single();
        } catch (RuntimeException exception) {
            dataSourceRepository.markFailed(
                    dataSourceId,
                    "Pokretanje importa nije uspelo: "
                            + exception.getMessage()
            );
            throw exception;
        }
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

    private void assignControlledCategory(Long retailerProductId) {
        jdbcClient.sql("""
                    INSERT INTO app.retailer_product_category AS assignment (
                        retailer_product_id,
                        product_category_id,
                        confidence,
                        assignment_source
                    )
                    SELECT product.id,
                           matched_category.product_category_id,
                           matched_category.confidence,
                           matched_category.assignment_source
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
                        ORDER BY candidate.priority ASC,
                                 candidate.confidence DESC,
                                 candidate.pattern_length DESC,
                                 candidate.id ASC
                        LIMIT 1
                    ) AS matched_category ON TRUE
                    WHERE product.id = ?
                    ON CONFLICT (retailer_product_id)
                    DO UPDATE SET
                        product_category_id = EXCLUDED.product_category_id,
                        confidence = EXCLUDED.confidence,
                        assignment_source = EXCLUDED.assignment_source,
                        updated_at = NOW()
                    WHERE assignment.reviewed = FALSE
                    """)
                .param(1, retailerProductId)
                .update();
    }

    private void savePriceSnapshot(
            Long retailerProductId,
            Long importRunId,
            String retailerFormatName,
            Long storeId,
            LocalDate priceDate,
            BigDecimal regularPrice,
            BigDecimal unitPrice,
            BigDecimal discountedPrice,
            LocalDate discountStartDate,
            LocalDate discountEndDate,
            BigDecimal vatRate
    ) {
        String normalizedFormatName = nullableText(retailerFormatName);

        /*
         * price_observation je istorija promena, a ne kopija svakog dnevnog
         * preseka. Aktuelna ponuda se osvežava pri svakom importu kako bi njen
         * last_seen_date ostao pouzdan signal svežine.
         */
        jdbcClient.sql("""
                    INSERT INTO app.price_observation (
                        retailer_product_id,
                        import_run_id,
                        retailer_format_name,
                        store_id,
                        price_date,
                        regular_price,
                        unit_price,
                        discounted_price,
                        discount_start,
                        discount_end,
                        vat_rate
                    )
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1
                        FROM app.current_price_offer AS current_offer
                        WHERE current_offer.retailer_product_id = ?
                          AND current_offer.scope_type = CASE
                              WHEN ? IS NOT NULL THEN 'STORE'
                              WHEN ? IS NOT NULL THEN 'STORE_FORMAT'
                              ELSE 'RETAILER'
                          END
                          AND current_offer.scope_key = CASE
                              WHEN ? IS NOT NULL
                                  THEN 'STORE:' || ?::TEXT
                              WHEN ? IS NOT NULL
                                  THEN 'STORE_FORMAT:' || LOWER(BTRIM(?))
                              ELSE 'RETAILER'
                          END
                          AND (
                              current_offer.regular_price,
                              current_offer.unit_price,
                              current_offer.discounted_price,
                              current_offer.discount_start,
                              current_offer.discount_end,
                              current_offer.vat_rate
                          ) IS NOT DISTINCT FROM (
                              ?::NUMERIC,
                              ?::NUMERIC,
                              ?::NUMERIC,
                              ?::DATE,
                              ?::DATE,
                              ?::NUMERIC
                          )
                    )
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
                        normalizedFormatName,
                        Types.VARCHAR
                )
                .param(4, storeId, Types.BIGINT)
                .param(5, priceDate, Types.DATE)
                .param(6, regularPrice, Types.NUMERIC)
                .param(7, unitPrice, Types.NUMERIC)
                .param(8, discountedPrice, Types.NUMERIC)
                .param(9, discountStartDate, Types.DATE)
                .param(10, discountEndDate, Types.DATE)
                .param(11, vatRate, Types.NUMERIC)
                .param(12, retailerProductId)
                .param(13, storeId, Types.BIGINT)
                .param(14, normalizedFormatName, Types.VARCHAR)
                .param(15, storeId, Types.BIGINT)
                .param(16, storeId, Types.BIGINT)
                .param(17, normalizedFormatName, Types.VARCHAR)
                .param(18, normalizedFormatName, Types.VARCHAR)
                .param(19, regularPrice, Types.NUMERIC)
                .param(20, unitPrice, Types.NUMERIC)
                .param(21, discountedPrice, Types.NUMERIC)
                .param(22, discountStartDate, Types.DATE)
                .param(23, discountEndDate, Types.DATE)
                .param(24, vatRate, Types.NUMERIC)
                .update();

        jdbcClient.sql("""
                    INSERT INTO app.current_price_offer AS current_offer (
                        retailer_product_id,
                        import_run_id,
                        scope_type,
                        retailer_format_name,
                        store_id,
                        price_date,
                        first_seen_date,
                        last_seen_date,
                        regular_price,
                        unit_price,
                        discounted_price,
                        discount_start,
                        discount_end,
                        vat_rate
                    )
                    VALUES (
                        ?, ?,
                        CASE
                            WHEN ? IS NOT NULL THEN 'STORE'
                            WHEN ? IS NOT NULL THEN 'STORE_FORMAT'
                            ELSE 'RETAILER'
                        END,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    ON CONFLICT (
                        retailer_product_id,
                        scope_type,
                        scope_key
                    )
                    DO UPDATE SET
                        import_run_id = EXCLUDED.import_run_id,
                        retailer_format_name = EXCLUDED.retailer_format_name,
                        store_id = EXCLUDED.store_id,
                        price_date = EXCLUDED.price_date,
                        first_seen_date = CASE
                            WHEN (
                                current_offer.regular_price,
                                current_offer.unit_price,
                                current_offer.discounted_price,
                                current_offer.discount_start,
                                current_offer.discount_end,
                                current_offer.vat_rate
                            ) IS NOT DISTINCT FROM (
                                EXCLUDED.regular_price,
                                EXCLUDED.unit_price,
                                EXCLUDED.discounted_price,
                                EXCLUDED.discount_start,
                                EXCLUDED.discount_end,
                                EXCLUDED.vat_rate
                            )
                                THEN LEAST(
                                    current_offer.first_seen_date,
                                    EXCLUDED.first_seen_date
                                )
                            ELSE EXCLUDED.first_seen_date
                        END,
                        last_seen_date = GREATEST(
                            current_offer.last_seen_date,
                            EXCLUDED.last_seen_date
                        ),
                        regular_price = EXCLUDED.regular_price,
                        unit_price = EXCLUDED.unit_price,
                        discounted_price = EXCLUDED.discounted_price,
                        discount_start = EXCLUDED.discount_start,
                        discount_end = EXCLUDED.discount_end,
                        vat_rate = EXCLUDED.vat_rate,
                        updated_at = NOW()
                    WHERE EXCLUDED.price_date >= current_offer.price_date
                    """)
                .param(1, retailerProductId)
                .param(2, importRunId)
                .param(3, storeId, Types.BIGINT)
                .param(4, normalizedFormatName, Types.VARCHAR)
                .param(5, normalizedFormatName, Types.VARCHAR)
                .param(6, storeId, Types.BIGINT)
                .param(7, priceDate, Types.DATE)
                .param(8, priceDate, Types.DATE)
                .param(9, priceDate, Types.DATE)
                .param(10, regularPrice, Types.NUMERIC)
                .param(11, unitPrice, Types.NUMERIC)
                .param(12, discountedPrice, Types.NUMERIC)
                .param(13, discountStartDate, Types.DATE)
                .param(14, discountEndDate, Types.DATE)
                .param(15, vatRate, Types.NUMERIC)
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

        ImportSourceState sourceState = findImportSourceState(importRunId);
        dataSourceRepository.markSucceeded(
                sourceState.dataSourceId(),
                status,
                snapshotDate,
                sourceState.checksum()
        );
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

        dataSourceRepository.markFailed(
                findImportSourceState(importRunId).dataSourceId(),
                errorMessage
        );
    }

    private ImportSourceState findImportSourceState(Long importRunId) {
        return jdbcClient.sql("""
                        SELECT data_source_id,
                               checksum
                        FROM app.import_run
                        WHERE id = ?
                        """)
                .param(1, importRunId)
                .query((resultSet, rowNumber) -> new ImportSourceState(
                        resultSet.getObject("data_source_id", Long.class),
                        resultSet.getString("checksum")
                ))
                .single();
    }

    private Reader createBomAwareReader(
            InputStream inputStream
    ) throws IOException {
        byte[] firstBytes = inputStream.readNBytes(4);
        Charset charset = StandardCharsets.UTF_8;
        int bomLength = 0;

        if (startsWith(firstBytes, 0xEF, 0xBB, 0xBF)) {
            bomLength = 3;
        } else if (startsWith(firstBytes, 0xFF, 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            bomLength = 2;
        } else if (startsWith(firstBytes, 0xFE, 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            bomLength = 2;
        } else if (
                firstBytes.length >= 2
                        && firstBytes[0] != 0
                        && firstBytes[1] == 0
        ) {
            charset = StandardCharsets.UTF_16LE;
        } else if (
                firstBytes.length >= 2
                        && firstBytes[0] == 0
                        && firstBytes[1] != 0
        ) {
            charset = StandardCharsets.UTF_16BE;
        }

        InputStream completeStream = new java.io.SequenceInputStream(
                new ByteArrayInputStream(
                        firstBytes,
                        bomLength,
                        firstBytes.length - bomLength
                ),
                inputStream
        );

        return new InputStreamReader(
                completeStream,
                charset
        );
    }

    private boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }

        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }

        return true;
    }

    private String column(CSVRecord record, String... aliases) {
        for (String alias : aliases) {
            if (record.isMapped(alias)) {
                if (!record.isSet(alias)) {
                    throw new IllegalArgumentException(
                            "CSV red nema vrednost za očekivanu kolonu: "
                                    + alias
                    );
                }

                return record.get(alias);
            }
        }

        throw new IllegalArgumentException(
                "CSV nema očekivanu kolonu: "
                        + String.join(" / ", aliases)
        );
    }

    private String optionalColumn(
            CSVRecord record,
            String... aliases
    ) {
        for (String alias : aliases) {
            if (record.isMapped(alias)) {
                return record.isSet(alias)
                        ? record.get(alias)
                        : null;
            }
        }

        return null;
    }

    private boolean isMonthlySnapshot(CSVRecord record) {
        String catalogType = optionalColumn(
                record,
                "VRSTA_CENOVNIKA"
        );

        if (catalogType == null || catalogType.isBlank()) {
            return false;
        }

        String normalizedType = Normalizer.normalize(
                        catalogType,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}+", "")
                .strip()
                .toUpperCase(Locale.ROOT);

        return "MESECNI_PRESEK".equals(normalizedType);
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
                column(record, "KATEGORIJA")
        );

        String categoryName = normalizeTextValue(
                column(record, "NAZIV KATEGORIJE")
        );

        String productName = requireText(
                column(record, "Naziv proizvoda"),
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
                column(record, "Robna marka")
        );

        String barcode = normalizeBarcode(
                column(record, "Barkod proizvoda")
        );

        String unitOfMeasure = normalizeTextValue(
                column(record, "Jedinica mere", "Jedinimere")
        );

        String retailerFormatName = requireText(
                column(
                        record,
                        "Naziv trgovca - formata*",
                        "Naziv trgovca – formata*",
                        "Naziv trgovca - formata",
                        "Naziv trgovca – formata"
                ),
                "Naziv trgovca - formata*"
        );

        BigDecimal regularPrice = positiveOrNull(
                parseDecimal(column(record, "Redovna cena"))
        );

        BigDecimal discountedPrice = positiveOrNull(
                parseDecimal(optionalColumn(
                        record,
                        "Snižena cena",
                        "Snizena cena"
                ))
        );

        /*
         * Red bez redovne i bez snižene cene nije upotrebljiv
         * za poređenje cena.
         */
        if (regularPrice == null && discountedPrice == null) {
            return null;
        }

        LocalDate priceDate = parseRequiredDate(
                column(record, "Datum cenovnika")
        );

        BigDecimal unitPrice = parseDecimal(
                column(record, "Cena po jedinici mere")
        );

        LocalDate discountStartDate = parseDate(
                optionalColumn(
                        record,
                        "Datum početka sniženja",
                        "Datum pocetka snizenja"
                )
        );

        LocalDate discountEndDate = parseDate(
                optionalColumn(
                        record,
                        "Datum kraja sniženja",
                        "Datum kraja snizenja"
                )
        );

        BigDecimal vatRate = parseDecimal(
                optionalColumn(record, "Stopa PDV")
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
                vatRate,
                null
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
        MessageDigest messageDigest = newSha256Digest();

        byte[] hash = messageDigest.digest(
                value.getBytes(StandardCharsets.UTF_8)
        );

        return HexFormat.of().formatHex(hash);
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algoritam nije dostupan.",
                    exception
            );
        }
    }

    private record DownloadedCsv(
            Path path,
            String checksum
    ) {
    }

    private record SnapshotScanResult(
            int rowsRead,
            int rowsWithErrors,
            LocalDate snapshotDate,
            boolean excludeMonthlySnapshots
    ) {
    }

    private record SnapshotWriteResult(
            int rowsSelected,
            int rowsSaved,
            int rowsWithErrors
    ) {
    }

    private record BatchWriteResult(
            int rowsSaved,
            int rowsWithErrors
    ) {
    }

    private record StorePriceTarget(
            long retailerId,
            long storeId,
            String formatName
    ) {
    }

    private record StoreSnapshotWriteResult(
            int rowsRead,
            int rowsSelected,
            int rowsSaved,
            int rowsWithErrors
    ) {
    }

    private record ImportSourceState(
            Long dataSourceId,
            String checksum
    ) {
    }
}
