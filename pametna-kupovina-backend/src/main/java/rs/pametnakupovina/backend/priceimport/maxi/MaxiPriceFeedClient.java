package rs.pametnakupovina.backend.priceimport.maxi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MaxiPriceFeedClient {

    private static final DateTimeFormatter REQUEST_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-uuuu");
    private static final Pattern STORE_FILE_PATTERN = Pattern.compile(
            "(?:^|/)(?:(?:MAXI|PRODAVNICA)_)?(\\d+)_.*\\.csv$",
            Pattern.CASE_INSENSITIVE
    );
    private static final String QUERY = """
            query GetDigitalPriceListsByDate(
                $date: String!
                $after: String
                $first: Int
            ) {
                digitalPriceListsByDate(
                    date: $date
                    after: $after
                    first: $first
                ) {
                    items {
                        name
                        path
                        lastModified
                    }
                    nextMarker
                }
            }
            """;

    private final RestClient restClient;
    private final String graphqlUrl;
    private final String staticBaseUrl;
    private final Set<String> storeCodes;
    private final Map<String, String> requiredFilePrefixes;
    private final int lookbackDays;

    @Autowired
    public MaxiPriceFeedClient(
            @Value("${maxi.price-import.graphql-url:https://www.maxi.rs/api/v1/}")
            String graphqlUrl,
            @Value("${maxi.price-import.static-base-url:https://static.maxi.rs/}")
            String staticBaseUrl,
            @Value("${maxi.price-import.store-codes:508,538,512,513,541,544}")
            String configuredStoreCodes,
            @Value("${maxi.price-import.store-file-prefixes:508=MAXI_508_KNEZA_MIHAJLA_84_86_VALJEVO_,538=MAXI_538_KARADJORDJEVA_92_VALJEVO_,512=PRODAVNICA_512_OBRENA_NIKOLICA_3_VALJEVO_,513=PRODAVNICA_513_NASELJE_ZBRATIMLJENI_GRADOVI_BB_VALJEVO_,541=MAXI_541_GODJEVACKA_2_DIVCIBARE_VALJEVO_,544=MAXI_544_KARADJORDJEVA_2_VALJEVO_}")
            String configuredFilePrefixes,
            @Value("${maxi.price-import.lookback-days:7}")
            int lookbackDays
    ) {
        this(
                RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Origin", "https://www.maxi.rs")
                .defaultHeader("Referer", "https://www.maxi.rs/cenovnici")
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build(),
                graphqlUrl,
                staticBaseUrl,
                configuredStoreCodes,
                configuredFilePrefixes,
                lookbackDays
        );
    }

    MaxiPriceFeedClient(
            RestClient restClient,
            String graphqlUrl,
            String staticBaseUrl,
            String configuredStoreCodes,
            String configuredFilePrefixes,
            int lookbackDays
    ) {
        this.restClient = restClient;
        this.graphqlUrl = graphqlUrl.strip();
        this.staticBaseUrl = ensureTrailingSlash(staticBaseUrl);
        this.storeCodes = parseStoreCodes(configuredStoreCodes);
        this.requiredFilePrefixes = parseFilePrefixes(
                configuredFilePrefixes
        );

        if (!requiredFilePrefixes.keySet().containsAll(storeCodes)) {
            throw new IllegalArgumentException(
                    "Svaka Maxi prodavnica mora imati jedinstveni "
                            + "prefiks cenovnika."
            );
        }

        this.lookbackDays = Math.max(lookbackDays, 1);
    }

    public List<MaxiPriceFile> findLatestFiles(LocalDate referenceDate) {
        for (int daysAgo = 0; daysAgo < lookbackDays; daysAgo++) {
            LocalDate candidateDate = referenceDate.minusDays(daysAgo);
            List<MaxiPriceFile> files = fetchFiles(candidateDate);

            if (!files.isEmpty()) {
                return files;
            }
        }

        throw new IllegalStateException(
                "Maxi cenovnici za podešene prodavnice nisu pronađeni "
                        + "u poslednjih "
                        + lookbackDays
                        + " dana."
        );
    }

    private List<MaxiPriceFile> fetchFiles(LocalDate snapshotDate) {
        Map<String, MaxiPriceFile> filesByStore = new LinkedHashMap<>();
        String nextMarker = null;

        do {
            JsonNode priceLists = requestPage(snapshotDate, nextMarker);
            JsonNode items = priceLists.path("items");

            if (items.isArray()) {
                for (JsonNode item : items) {
                    addConfiguredStoreFile(
                            filesByStore,
                            item,
                            snapshotDate
                    );
                }
            }

            nextMarker = nullableText(
                    priceLists.path("nextMarker").asText()
            );
        } while (nextMarker != null);

        return List.copyOf(filesByStore.values());
    }

    private JsonNode requestPage(
            LocalDate snapshotDate,
            String nextMarker
    ) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(
                "date",
                REQUEST_DATE_FORMAT.format(snapshotDate)
        );
        variables.put("after", nextMarker);
        variables.put("first", 50);

        JsonNode body = restClient.post()
                .uri(graphqlUrl)
                .body(Map.of(
                        "query", QUERY,
                        "variables", variables
                ))
                .retrieve()
                .body(JsonNode.class);

        if (body == null) {
            throw new IllegalStateException(
                    "Maxi API nije vratio odgovor."
            );
        }

        JsonNode errors = body.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException(
                    "Maxi API je vratio GraphQL grešku: "
                            + errors.get(0).path("message").asText()
            );
        }

        JsonNode priceLists = body.path("data")
                .path("digitalPriceListsByDate");

        if (priceLists.isMissingNode() || priceLists.isNull()) {
            throw new IllegalStateException(
                    "Maxi API odgovor nema očekivanu listu cenovnika."
            );
        }

        return priceLists;
    }

    private void addConfiguredStoreFile(
            Map<String, MaxiPriceFile> filesByStore,
            JsonNode item,
            LocalDate snapshotDate
    ) {
        String name = nullableText(item.path("name").asText());
        String path = nullableText(item.path("path").asText());

        if (name == null || path == null) {
            return;
        }

        Matcher matcher = STORE_FILE_PATTERN.matcher(name);
        if (!matcher.find()) {
            return;
        }

        String storeCode = matcher.group(1);
        if (!storeCodes.contains(storeCode)) {
            return;
        }

        String fileName = name.substring(name.lastIndexOf('/') + 1);
        String requiredPrefix = requiredFilePrefixes.get(storeCode);

        if (!fileName.toUpperCase(Locale.ROOT)
                .startsWith(requiredPrefix)) {
            return;
        }

        filesByStore.putIfAbsent(
                storeCode,
                new MaxiPriceFile(
                        storeCode,
                        name,
                        buildStaticUrl(path),
                        snapshotDate
                )
        );
    }

    private String buildStaticUrl(String path) {
        String normalizedPath = path;

        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }

        return staticBaseUrl + normalizedPath;
    }

    private Set<String> parseStoreCodes(String configuredStoreCodes) {
        Set<String> parsedCodes = new LinkedHashSet<>();

        for (String storeCode : configuredStoreCodes.split(",")) {
            String normalized = nullableText(storeCode);
            if (normalized != null) {
                parsedCodes.add(normalized.toUpperCase(Locale.ROOT));
            }
        }

        if (parsedCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Mora biti podešena bar jedna Maxi prodavnica."
            );
        }

        return Set.copyOf(parsedCodes);
    }

    private Map<String, String> parseFilePrefixes(
            String configuredFilePrefixes
    ) {
        Map<String, String> parsedPrefixes = new LinkedHashMap<>();

        for (String mapping : configuredFilePrefixes.split(",")) {
            String normalizedMapping = nullableText(mapping);

            if (normalizedMapping == null) {
                continue;
            }

            int separatorIndex = normalizedMapping.indexOf('=');

            if (separatorIndex <= 0
                    || separatorIndex == normalizedMapping.length() - 1) {
                throw new IllegalArgumentException(
                        "Neispravan Maxi prefiks cenovnika: "
                                + normalizedMapping
                );
            }

            String storeCode = normalizedMapping
                    .substring(0, separatorIndex)
                    .strip()
                    .toUpperCase(Locale.ROOT);
            String prefix = normalizedMapping
                    .substring(separatorIndex + 1)
                    .strip()
                    .toUpperCase(Locale.ROOT);

            parsedPrefixes.put(storeCode, prefix);
        }

        return Map.copyOf(parsedPrefixes);
    }

    private String nullableText(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.strip();
        return normalized.isEmpty() || "null".equalsIgnoreCase(normalized)
                ? null
                : normalized;
    }

    private static String ensureTrailingSlash(String value) {
        String normalized = value.strip();
        return normalized.endsWith("/")
                ? normalized
                : normalized + "/";
    }

}
