package rs.pametnakupovina.backend.storepricing;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.priceimport.GovernmentDataResourceDiscoveryClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class IdeaRodaPriceFormatMappingClient {

    private static final Pattern STORE_NAME = Pattern.compile(
            "^(MP\\d+)\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final int MAX_HEADER_ROWS = 30;
    private static final int MIN_ASSIGNMENTS = 200;
    private static final int MIN_RODA_ASSIGNMENTS = 20;
    private static final int MAX_ASSIGNMENTS = 5_000;
    private static final String FORMAT_PREFIX =
            "IDEA MARKETI_Cenovnik ";

    private final GovernmentDataResourceDiscoveryClient discoveryClient;
    private final HttpClient httpClient;
    private final String discoveryUrl;
    private final Duration requestTimeout;
    private final long maxDownloadBytes;

    @Autowired
    public IdeaRodaPriceFormatMappingClient(
            GovernmentDataResourceDiscoveryClient discoveryClient,
            @Value("${idea.price-format-mapping.discovery-url}")
            String discoveryUrl,
            @Value("${price-import.http.connect-timeout-seconds:20}")
            long connectTimeoutSeconds,
            @Value("${price-import.discovery-timeout-seconds:60}")
            long requestTimeoutSeconds,
            @Value("${idea.price-format-mapping.max-download-bytes:10000000}")
            long maxDownloadBytes
    ) {
        this(
                discoveryClient,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(
                                connectTimeoutSeconds
                        ))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                discoveryUrl,
                Duration.ofSeconds(requestTimeoutSeconds),
                maxDownloadBytes
        );
    }

    IdeaRodaPriceFormatMappingClient(
            GovernmentDataResourceDiscoveryClient discoveryClient,
            HttpClient httpClient,
            String discoveryUrl,
            Duration requestTimeout,
            long maxDownloadBytes
    ) {
        if (maxDownloadBytes <= 0) {
            throw new IllegalArgumentException(
                    "Maksimalna veličina XLSX fajla mora biti pozitivna."
            );
        }
        this.discoveryClient = discoveryClient;
        this.httpClient = httpClient;
        this.discoveryUrl = discoveryUrl;
        this.requestTimeout = requestTimeout;
        this.maxDownloadBytes = maxDownloadBytes;
    }

    public StorePriceFormatSnapshot fetchLatest() {
        var resource = discoveryClient
                .discoverLatestStoreMappingSpreadsheet(discoveryUrl);
        byte[] workbookBytes = download(resource.url());
        List<OfficialStorePriceFormat> assignments =
                parseWorkbook(workbookBytes);
        validateOfficialVolume(assignments);
        return new StorePriceFormatSnapshot(
                resource.url(),
                resource.lastModified(),
                assignments
        );
    }

    List<OfficialStorePriceFormat> parseWorkbook(byte[] workbookBytes) {
        try (
                Workbook workbook = WorkbookFactory.create(
                        new ByteArrayInputStream(workbookBytes)
                )
        ) {
            HeaderLocation header = findHeader(workbook);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Map<String, OfficialStorePriceFormat> assignments =
                    new LinkedHashMap<>();

            for (int rowIndex = header.rowIndex() + 1;
                 rowIndex <= header.sheet().getLastRowNum();
                 rowIndex++) {
                Row row = header.sheet().getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                String rawStoreName = formatter.formatCellValue(
                        row.getCell(header.storeNameColumn())
                ).strip();
                String rawFormat = formatter.formatCellValue(
                        row.getCell(header.priceFormatColumn())
                ).strip();

                if (rawStoreName.isBlank() && rawFormat.isBlank()) {
                    continue;
                }

                Matcher storeMatcher = STORE_NAME.matcher(rawStoreName);
                if (!storeMatcher.matches()) {
                    throw new IllegalStateException(
                            "Nepoznata šifra objekta u XLSX redu "
                                    + (rowIndex + 1)
                                    + ": "
                                    + rawStoreName
                    );
                }

                String sourceStoreCode = storeMatcher.group(1)
                        .toUpperCase(Locale.ROOT);
                String storeName = storeMatcher.group(2).strip();
                String priceFormatCode = normalizePriceFormat(rawFormat);
                OfficialStorePriceFormat assignment =
                        new OfficialStorePriceFormat(
                                sourceStoreCode,
                                storeName,
                                priceFormatCode
                        );
                OfficialStorePriceFormat previous = assignments.putIfAbsent(
                        sourceStoreCode,
                        assignment
                );

                if (previous != null
                        && !previous.priceFormatCode().equals(
                        assignment.priceFormatCode()
                )) {
                    throw new IllegalStateException(
                            "Objekat "
                                    + sourceStoreCode
                                    + " ima više cenovnika u istom XLSX fajlu."
                    );
                }
                if (assignments.size() > MAX_ASSIGNMENTS) {
                    throw new IllegalStateException(
                            "XLSX ima neočekivano mnogo objekata."
                    );
                }
            }

            if (assignments.isEmpty()) {
                throw new IllegalStateException(
                        "XLSX ne sadrži nijednu vezu objekta i cenovnika."
                );
            }
            return List.copyOf(assignments.values());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Čitanje pregleda cenovnika po objektima nije uspelo.",
                    exception
            );
        }
    }

    private HeaderLocation findHeader(Workbook workbook) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (Sheet sheet : workbook) {
            int finalHeaderRow = Math.min(
                    sheet.getLastRowNum(),
                    MAX_HEADER_ROWS - 1
            );
            for (int rowIndex = 0;
                 rowIndex <= finalHeaderRow;
                 rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }

                Integer storeNameColumn = null;
                Integer priceFormatColumn = null;
                for (int column = 0;
                     column < row.getLastCellNum();
                     column++) {
                    String header = normalizeHeader(
                            formatter.formatCellValue(row.getCell(column))
                    );
                    if ("NAZIV OBJEKTA".equals(header)) {
                        storeNameColumn = column;
                    } else if ("CENOVNIK".equals(header)) {
                        priceFormatColumn = column;
                    }
                }

                if (storeNameColumn != null
                        && priceFormatColumn != null) {
                    return new HeaderLocation(
                            sheet,
                            rowIndex,
                            storeNameColumn,
                            priceFormatColumn
                    );
                }
            }
        }
        throw new IllegalStateException(
                "XLSX nema kolone NAZIV OBJEKTA i CENOVNIK."
        );
    }

    private String normalizePriceFormat(String rawFormat) {
        String normalized = rawFormat.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "i0" -> "I0";
            case "iminus" -> "Iminus";
            case "iplus" -> "Iplus";
            case "rbase" -> "Rbase";
            case "rplus" -> "Rplus";
            default -> throw new IllegalStateException(
                    "Nepoznat IDEA/Roda cenovnik: " + rawFormat
            );
        };
    }

    static String retailerFormatName(String priceFormatCode) {
        return FORMAT_PREFIX + priceFormatCode;
    }

    private void validateOfficialVolume(
            List<OfficialStorePriceFormat> assignments
    ) {
        long rodaAssignments = assignments.stream()
                .filter(assignment -> assignment.priceFormatCode()
                        .startsWith("R"))
                .count();
        if (assignments.size() < MIN_ASSIGNMENTS
                || rodaAssignments < MIN_RODA_ASSIGNMENTS) {
            throw new IllegalStateException(
                    "Zvanični XLSX ima neočekivano mali broj IDEA/Roda "
                            + "objekata; postojeće mape nisu promenjene."
            );
        }
    }

    private String normalizeHeader(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    private byte[] download(String sourceUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
                .timeout(requestTimeout)
                .header(
                        "Accept",
                        "application/vnd.openxmlformats-officedocument."
                                + "spreadsheetml.sheet"
                )
                .header("User-Agent", "PametnaKupovina/1.0")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalStateException(
                        "Preuzimanje XLSX fajla nije uspelo: HTTP "
                                + response.statusCode()
                );
            }

            long contentLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (contentLength > maxDownloadBytes) {
                response.body().close();
                throw new IllegalStateException(
                        "XLSX fajl je veći od dozvoljenog maksimuma."
                );
            }

            try (InputStream input = response.body()) {
                return readBounded(input);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Preuzimanje XLSX fajla je prekinuto.",
                    exception
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Preuzimanje XLSX fajla nije uspelo.",
                    exception
            );
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8_192];
        long bytesRead = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            bytesRead += count;
            if (bytesRead > maxDownloadBytes) {
                throw new IllegalStateException(
                        "XLSX fajl je veći od dozvoljenog maksimuma."
                );
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private record HeaderLocation(
            Sheet sheet,
            int rowIndex,
            int storeNameColumn,
            int priceFormatColumn
    ) {
    }
}
