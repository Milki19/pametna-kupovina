package rs.pametnakupovina.backend.retailerlocation.maxi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class MaxiLocationClient {

    private static final String STORE_SEARCH_QUERY = """
            query GetStoreSearch(
                $lang: String!
                $query: String
                $latitude: Float
                $longitude: Float
                $radius: Float
                $pageSize: Int
                $currentPage: Int
                $sort: String
                $collectionFlow: Boolean
                $options: String
            ) {
                storeSearchJSON(
                    lang: $lang
                    query: $query
                    latitude: $latitude
                    longitude: $longitude
                    radius: $radius
                    pageSize: $pageSize
                    currentPage: $currentPage
                    sort: $sort
                    collectionFlow: $collectionFlow
                    options: $options
                )
            }
            """;

    private static final int PAGE_SIZE = 2000;
    private static final int MAX_PAGES = 20;

    private static final int ATTEMPTS = 3;
    private static final Logger log =
            LoggerFactory.getLogger(MaxiLocationClient.class);

    private final RestClient restClient;
    private final String sourceUrl;
    private final Duration retryPause;

    @Autowired
    MaxiLocationClient(
            @Value("${maxi.location-import.graphql-url}")
            String sourceUrl,
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
        requestFactory.setReadTimeout(Duration.ofSeconds(requestTimeoutSeconds));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Origin", "https://www.maxi.rs")
                .defaultHeader("Referer", "https://www.maxi.rs/storelocator")
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
        this.sourceUrl = sourceUrl;
        this.retryPause = Duration.ofSeconds(30);
    }

    MaxiLocationClient(RestClient restClient, String sourceUrl) {
        this(restClient, sourceUrl, Duration.ZERO);
    }

    MaxiLocationClient(
            RestClient restClient,
            String sourceUrl,
            Duration retryPause
    ) {
        this.restClient = restClient;
        this.sourceUrl = sourceUrl;
        this.retryPause = retryPause;
    }

    /**
     * Reads every page the locator offers. Maxi may serve fewer stores per
     * page than asked for, so the pages are followed until the promised total
     * is reached; a set that still falls short is refused, because missing
     * stores would otherwise be closed.
     */
    List<MaxiApiLocation> fetchLocations() {
        Map<String, JsonNode> storesById = new LinkedHashMap<>();
        List<JsonNode> storesWithoutId = new ArrayList<>();
        int expectedTotal = -1;

        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode search = fetchPage(page);
            JsonNode stores = search.path("stores");
            if (!stores.isArray() || stores.isEmpty()) {
                if (page == 0) {
                    throw new IllegalStateException(
                            "Zvanični Maxi lokator nije vratio lokacije."
                    );
                }
                break;
            }
            if (page == 0) {
                expectedTotal = search.path("pagination")
                        .path("totalResults")
                        .asInt(-1);
            }

            int before = storesById.size() + storesWithoutId.size();
            for (JsonNode store : stores) {
                String id = store.path("id").asText().strip();
                if (id.isEmpty()) {
                    storesWithoutId.add(store);
                } else {
                    storesById.putIfAbsent(id, store);
                }
            }
            int collected = storesById.size() + storesWithoutId.size();
            if (expectedTotal <= 0
                    || collected >= expectedTotal
                    || collected == before) {
                break;
            }
        }

        int collected = storesById.size() + storesWithoutId.size();
        if (expectedTotal > 0 && collected < expectedTotal) {
            throw new IllegalStateException(
                    "Zvanični Maxi lokator je vratio nepotpun skup: "
                            + collected + "/" + expectedTotal
            );
        }

        List<MaxiApiLocation> locations = new ArrayList<>(collected);
        storesById.values().forEach(store -> locations.add(location(store)));
        storesWithoutId.forEach(store -> locations.add(location(store)));
        return List.copyOf(locations);
    }

    /**
     * The locator runs once a week, so one dropped connection or a slow
     * moment at Maxi would leave the stores a whole week old. A failed page
     * is asked for again a few times before the run gives up.
     */
    private JsonNode fetchPage(int page) {
        RestClientException lastFailure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return fetchPageOnce(page);
            } catch (RestClientException exception) {
                lastFailure = exception;
                log.warn(
                        "Maxi lokator, strana {}, pokušaj {}/{} nije uspeo: {}",
                        page,
                        attempt,
                        ATTEMPTS,
                        exception.getMessage()
                );
                if (attempt < ATTEMPTS) {
                    pause();
                }
            }
        }
        throw lastFailure;
    }

    private void pause() {
        try {
            Thread.sleep(retryPause.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Čekanje na Maxi lokator je prekinuto.",
                    interrupted
            );
        }
    }

    private JsonNode fetchPageOnce(int page) {
        JsonNode response = restClient.post()
                .uri(sourceUrl)
                .body(Map.of(
                        "query",
                        STORE_SEARCH_QUERY,
                        "variables",
                        Map.of(
                                "lang", "sr",
                                "query", "",
                                "latitude", 44.2,
                                "longitude", 20.8,
                                "radius", 1000,
                                "pageSize", PAGE_SIZE,
                                "currentPage", page,
                                "sort", "distance",
                                "collectionFlow", false
                        )
                ))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException(
                    "Zvanični Maxi lokator nije vratio odgovor."
            );
        }
        JsonNode errors = response.path("errors");
        JsonNode search = response.path("data").path("storeSearchJSON");
        if (!search.isObject()) {
            throw new IllegalStateException(
                    "Zvanični Maxi lokator nije vratio pretragu prodavnica"
                            + (errors.isArray() && !errors.isEmpty()
                                    ? ": " + errors.get(0)
                                            .path("message")
                                            .asText()
                                    : ".")
            );
        }
        return search;
    }

    /**
     * One store in the shape the import expects. A field the locator left
     * out stays empty, so only that store is skipped later, not the chain.
     */
    private MaxiApiLocation location(JsonNode store) {
        JsonNode address = store.path("address");
        JsonNode geoPoint = store.path("geoPoint");
        return new MaxiApiLocation(
                text(store, "id"),
                preferredText(store, "localizedName", "description"),
                preferredText(address, "line1", "formattedAddress"),
                text(address, "town"),
                text(store, "groceryStoreType"),
                number(geoPoint, "latitude"),
                number(geoPoint, "longitude")
        );
    }

    private String preferredText(
            JsonNode node,
            String preferredField,
            String fallbackField
    ) {
        String preferred = text(node, preferredField);
        return preferred == null ? text(node, fallbackField) : preferred;
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText().strip();
        return value.isEmpty() ? null : value;
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asDouble() : Double.NaN;
    }
}
