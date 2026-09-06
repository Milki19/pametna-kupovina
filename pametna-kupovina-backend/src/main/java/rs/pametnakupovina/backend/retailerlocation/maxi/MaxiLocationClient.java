package rs.pametnakupovina.backend.retailerlocation.maxi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
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

    private final RestClient restClient;
    private final String sourceUrl;

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
    }

    MaxiLocationClient(RestClient restClient, String sourceUrl) {
        this.restClient = restClient;
        this.sourceUrl = sourceUrl;
    }

    List<MaxiApiLocation> fetchLocations() {
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
                                "pageSize", 2000,
                                "currentPage", 0,
                                "sort", "distance",
                                "collectionFlow", false
                        )
                ))
                .retrieve()
                .body(JsonNode.class);

        JsonNode search = response == null
                ? null
                : response.path("data").path("storeSearchJSON");
        JsonNode stores = search == null ? null : search.path("stores");
        if (stores == null || !stores.isArray() || stores.isEmpty()) {
            throw new IllegalStateException(
                    "Zvanični Maxi lokator nije vratio lokacije."
            );
        }

        int expectedTotal = search.path("pagination")
                .path("totalResults")
                .asInt(-1);
        if (expectedTotal > 0 && stores.size() < expectedTotal) {
            throw new IllegalStateException(
                    "Zvanični Maxi lokator je vratio nepotpun skup: "
                            + stores.size() + "/" + expectedTotal
            );
        }

        List<MaxiApiLocation> locations = new ArrayList<>(stores.size());
        for (JsonNode store : stores) {
            JsonNode address = store.path("address");
            JsonNode geoPoint = store.path("geoPoint");
            locations.add(new MaxiApiLocation(
                    text(store, "id"),
                    preferredText(store, "localizedName", "description"),
                    preferredText(address, "line1", "formattedAddress"),
                    text(address, "town"),
                    text(store, "groceryStoreType"),
                    number(geoPoint, "latitude"),
                    number(geoPoint, "longitude")
            ));
        }

        return List.copyOf(locations);
    }

    private String preferredText(
            JsonNode node,
            String preferredField,
            String fallbackField
    ) {
        String preferred = node.path(preferredField).asText().strip();
        return preferred.isEmpty() ? text(node, fallbackField) : preferred;
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText().strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Maxi lokacija nema polje: " + field
            );
        }
        return value;
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException(
                    "Maxi lokacija ima neispravnu koordinatu: " + field
            );
        }
        return value.asDouble();
    }
}
