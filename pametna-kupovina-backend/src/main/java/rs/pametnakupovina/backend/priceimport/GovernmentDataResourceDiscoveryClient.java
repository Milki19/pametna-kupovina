package rs.pametnakupovina.backend.priceimport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

@Component
public class GovernmentDataResourceDiscoveryClient {

    private final RestClient restClient;

    @Autowired
    public GovernmentDataResourceDiscoveryClient(
            @Value("${price-import.http.connect-timeout-seconds:20}")
            long connectTimeoutSeconds,
            @Value("${price-import.discovery-timeout-seconds:60}")
            long requestTimeoutSeconds
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(
                Duration.ofSeconds(requestTimeoutSeconds)
        );

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
    }

    GovernmentDataResourceDiscoveryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public List<GovernmentPriceDataset> discoverPriceDatasets(
            String catalogApiUrl,
            int maximumPages
    ) {
        if (maximumPages < 1 || maximumPages > 100) {
            throw new IllegalArgumentException(
                    "Broj stranica API kataloga mora biti između 1 i 100."
            );
        }

        URI nextPage = URI.create(catalogApiUrl);
        validateCatalogApiOrigin(nextPage);

        Map<String, GovernmentPriceDataset> discovered =
                new LinkedHashMap<>();

        for (int page = 0;
             page < maximumPages && nextPage != null;
             page++) {
            JsonNode response = restClient.get()
                    .uri(nextPage)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("data").isArray()) {
                throw new IllegalStateException(
                        "data.gov.rs API katalog nema listu skupova."
                );
            }

            for (JsonNode dataset : response.path("data")) {
                toPriceDataset(dataset, nextPage)
                        .ifPresent(candidate -> discovered.put(
                                candidate.portalDatasetId(),
                                candidate
                        ));
            }

            nextPage = nextCatalogPage(response);
        }

        return new ArrayList<>(discovered.values());
    }

    public DiscoveredCsvResource discoverLatestCsv(
            String discoveryUrl
    ) {
        URI apiUri = toDatasetApiUri(discoveryUrl);
        JsonNode response = restClient.get()
                .uri(apiUri)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException(
                    "data.gov.rs API nije vratio odgovor."
            );
        }

        JsonNode resources = response.path("resources");
        if (!resources.isArray()) {
            throw new IllegalStateException(
                    "data.gov.rs odgovor nema listu resursa."
            );
        }

        return StreamSupport.stream(resources.spliterator(), false)
                .filter(this::isCsv)
                .filter(this::isPriceCatalog)
                .map(this::toResource)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(
                        DiscoveredCsvResource::lastModified
                ))
                .orElseThrow(() -> new IllegalStateException(
                        "data.gov.rs skup nema dostupan CSV cenovnik."
                ));
    }

    public DiscoveredSpreadsheetResource
    discoverLatestStoreMappingSpreadsheet(String discoveryUrl) {
        URI apiUri = toDatasetApiUri(discoveryUrl);
        JsonNode response = restClient.get()
                .uri(apiUri)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException(
                    "data.gov.rs API nije vratio odgovor."
            );
        }

        JsonNode resources = response.path("resources");
        if (!resources.isArray()) {
            throw new IllegalStateException(
                    "data.gov.rs odgovor nema listu resursa."
            );
        }

        return StreamSupport.stream(resources.spliterator(), false)
                .filter(this::isSpreadsheet)
                .filter(this::isStoreMappingSpreadsheet)
                .map(this::toSpreadsheetResource)
                .flatMap(Optional::stream)
                .max(Comparator.comparing(
                        DiscoveredSpreadsheetResource::lastModified
                ))
                .orElseThrow(() -> new IllegalStateException(
                        "data.gov.rs skup nema pregled cenovnika "
                                + "po objektima."
                ));
    }

    private URI toDatasetApiUri(String discoveryUrl) {
        URI discoveryUri = URI.create(discoveryUrl);
        validateDiscoveryOrigin(discoveryUri);

        String path = discoveryUri.getPath();
        int datasetsIndex = path.indexOf("/datasets/");
        if (datasetsIndex < 0) {
            throw new IllegalArgumentException(
                    "Discovery URL mora voditi na data.gov.rs dataset."
            );
        }

        String slug = path.substring(
                datasetsIndex + "/datasets/".length()
        );
        while (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }

        if (slug.isBlank() || slug.contains("/")) {
            throw new IllegalArgumentException(
                    "Discovery URL nema ispravan dataset slug."
            );
        }

        return URI.create(
                discoveryUri.getScheme()
                        + "://"
                        + discoveryUri.getAuthority()
                        + "/api/1/datasets/"
                        + slug
                        + "/"
        );
    }

    private Optional<GovernmentPriceDataset> toPriceDataset(
            JsonNode dataset,
            URI catalogPage
    ) {
        if (!isPriceDataset(dataset)) {
            return Optional.empty();
        }

        JsonNode resources = dataset.path("resources");
        if (!resources.isArray()) {
            return Optional.empty();
        }

        Optional<DetailedCsvResource> latestResource =
                StreamSupport.stream(resources.spliterator(), false)
                        .filter(this::isCsv)
                        .filter(this::isPriceCatalog)
                        .map(this::toDetailedResource)
                        .flatMap(Optional::stream)
                        .max(Comparator.comparing(
                                DetailedCsvResource::lastModified
                        ));

        if (latestResource.isEmpty()) {
            return Optional.empty();
        }

        String slug = dataset.path("slug").asText("").strip();
        String portalId = dataset.path("id").asText("").strip();
        String title = dataset.path("title").asText("").strip();

        if (slug.isBlank() || title.isBlank()) {
            return Optional.empty();
        }

        if (portalId.isBlank()) {
            portalId = slug;
        }

        String pageUrl = dataset.path("page").asText("").strip();
        if (pageUrl.isBlank()) {
            pageUrl = catalogPage.getScheme()
                    + "://"
                    + catalogPage.getAuthority()
                    + "/sr/datasets/"
                    + slug
                    + "/";
        }

        DetailedCsvResource resource = latestResource.get();
        return Optional.of(new GovernmentPriceDataset(
                portalId,
                slug,
                title,
                dataset.path("organization").path("name")
                        .asText("").strip(),
                pageUrl,
                resource.id(),
                resource.title(),
                resource.url(),
                resource.format(),
                resource.lastModified()
        ));
    }

    private Optional<DetailedCsvResource> toDetailedResource(
            JsonNode resource
    ) {
        Optional<DiscoveredCsvResource> safeResource =
                toResource(resource);

        return safeResource.map(discovered -> new DetailedCsvResource(
                resource.path("id").asText("").strip(),
                resource.path("title").asText("").strip(),
                discovered.url(),
                "CSV",
                discovered.lastModified()
        ));
    }

    private boolean isPriceDataset(JsonNode dataset) {
        StringBuilder identity = new StringBuilder()
                .append(dataset.path("title").asText(""))
                .append(' ')
                .append(dataset.path("description").asText(""));

        JsonNode tags = dataset.path("tags");
        if (tags.isArray()) {
            for (JsonNode tag : tags) {
                identity.append(' ')
                        .append(tag.path("name").asText(""))
                        .append(' ')
                        .append(tag.path("display_name").asText(""));
            }
        }

        String normalized = identity.toString().toLowerCase(Locale.ROOT);
        return normalized.contains("cenovnik")
                || normalized.contains("cenovnici")
                || normalized.contains("cene proizvoda")
                || normalized.contains("ценовник")
                || normalized.contains("цене производа");
    }

    private URI nextCatalogPage(JsonNode response) {
        String nextPage = response.path("next_page")
                .asText("")
                .strip();

        if (nextPage.isBlank()) {
            return null;
        }

        URI nextUri = URI.create(nextPage);
        validateCatalogApiOrigin(nextUri);
        return nextUri;
    }

    private void validateDiscoveryOrigin(URI uri) {
        String host = uri.getHost();
        boolean officialSource = "https".equalsIgnoreCase(uri.getScheme())
                && "data.gov.rs".equalsIgnoreCase(host);
        boolean localTestSource = "http".equalsIgnoreCase(uri.getScheme())
                && isLoopback(host);

        if (!officialSource && !localTestSource) {
            throw new IllegalArgumentException(
                    "Discovery je dozvoljen samo preko zvaničnog "
                            + "data.gov.rs API-ja."
            );
        }
    }

    private void validateCatalogApiOrigin(URI uri) {
        validateDiscoveryOrigin(uri);

        if (!uri.getPath().startsWith("/api/1/datasets/")) {
            throw new IllegalArgumentException(
                    "Discovery kataloga mora koristiti data.gov.rs API."
            );
        }
    }

    private boolean isCsv(JsonNode resource) {
        String format = resource.path("format")
                .asText("")
                .toLowerCase(Locale.ROOT);
        String mime = resource.path("mime")
                .asText("")
                .toLowerCase(Locale.ROOT);
        String title = resource.path("title")
                .asText("")
                .toLowerCase(Locale.ROOT);

        return format.equals("csv")
                || mime.equals("text/csv")
                || title.endsWith(".csv");
    }

    private boolean isPriceCatalog(JsonNode resource) {
        String identity = (
                resource.path("title").asText("")
                        + " "
                        + resource.path("url").asText("")
        ).toLowerCase(Locale.ROOT);

        // Pojedini skupovi uz cenovnik objavljuju i noviji pomoćni CSV,
        // npr. Lidl ean-DD-MM-YYYY.csv. Import cenovnika mora birati glavnu
        // datoteku sa cenama, a ne samo poslednji CSV po vremenu izmene.
        if (identity.contains("/ean")
                || identity.contains(" ean")) {
            return false;
        }

        return identity.contains("cene-proizvoda")
                || identity.contains("cene_proizvoda")
                || identity.contains("cene proizvoda")
                || identity.contains("cenovnik")
                || identity.contains("cenovnici")
                || identity.contains("/cene")
                || identity.contains(" cene");
    }

    private boolean isSpreadsheet(JsonNode resource) {
        String format = resource.path("format")
                .asText("")
                .toLowerCase(Locale.ROOT);
        String mime = resource.path("mime")
                .asText("")
                .toLowerCase(Locale.ROOT);
        String title = resource.path("title")
                .asText("")
                .toLowerCase(Locale.ROOT);

        return format.equals("xlsx")
                || mime.equals(
                        "application/vnd.openxmlformats-officedocument."
                                + "spreadsheetml.sheet"
                )
                || title.endsWith(".xlsx");
    }

    private boolean isStoreMappingSpreadsheet(JsonNode resource) {
        String identity = (
                resource.path("title").asText("")
                        + " "
                        + resource.path("description").asText("")
                        + " "
                        + resource.path("url").asText("")
        ).toLowerCase(Locale.ROOT);

        return identity.contains("maloprodajni")
                || identity.contains("objekt")
                || identity.contains("prodavnic");
    }

    private Optional<DiscoveredCsvResource> toResource(
            JsonNode resource
    ) {
        String url = resource.path("url").asText("").strip();
        if (url.isBlank()) {
            return Optional.empty();
        }

        URI resourceUri = URI.create(url);
        String host = resourceUri.getHost();
        boolean officialOrPublisherResource =
                isSafeHttpsResource(resourceUri);
        boolean localTestResource =
                "http".equalsIgnoreCase(resourceUri.getScheme())
                        && isLoopback(host);

        if (!officialOrPublisherResource && !localTestResource) {
            return Optional.empty();
        }

        return Optional.of(new DiscoveredCsvResource(
                url,
                parseInstant(resource.path("last_modified").asText(""))
        ));
    }

    private Optional<DiscoveredSpreadsheetResource> toSpreadsheetResource(
            JsonNode resource
    ) {
        String url = resource.path("url").asText("").strip();
        if (url.isBlank()) {
            return Optional.empty();
        }

        URI resourceUri = URI.create(url);
        String host = resourceUri.getHost();
        boolean officialOrPublisherResource =
                isSafeHttpsResource(resourceUri);
        boolean localTestResource =
                "http".equalsIgnoreCase(resourceUri.getScheme())
                        && isLoopback(host);

        if (!officialOrPublisherResource && !localTestResource) {
            return Optional.empty();
        }

        return Optional.of(new DiscoveredSpreadsheetResource(
                url,
                parseInstant(resource.path("last_modified").asText(""))
        ));
    }

    private boolean isSafeHttpsResource(URI uri) {
        String host = uri.getHost();

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || host.isBlank()
                || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);

        return !isLoopback(normalizedHost)
                && !normalizedHost.startsWith("10.")
                && !normalizedHost.startsWith("127.")
                && !normalizedHost.startsWith("169.254.")
                && !normalizedHost.startsWith("192.168.")
                && !normalizedHost.equals("0.0.0.0")
                && !normalizedHost.equals("::")
                && !normalizedHost.startsWith("fc")
                && !normalizedHost.startsWith("fd")
                && !normalizedHost.startsWith("fe80:");
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Instant.EPOCH;
        }
    }

    private static boolean isLoopback(String host) {
        return host != null && (
                host.equalsIgnoreCase("localhost")
                        || host.equals("127.0.0.1")
                        || host.equals("::1")
        );
    }

    public record DiscoveredCsvResource(
            String url,
            Instant lastModified
    ) {
    }

    public record DiscoveredSpreadsheetResource(
            String url,
            Instant lastModified
    ) {
    }

    private record DetailedCsvResource(
            String id,
            String title,
            String url,
            String format,
            Instant lastModified
    ) {
    }
}
