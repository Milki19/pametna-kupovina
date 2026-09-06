package rs.pametnakupovina.backend.retailerlocation.univerexport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
class UniverexportLocationClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String sourceUrl;

    @Autowired
    UniverexportLocationClient(
            @Value("${univerexport.location-import.url}")
            String sourceUrl,
            ObjectMapper objectMapper,
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
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
        this.objectMapper = objectMapper;
        this.sourceUrl = sourceUrl;
    }

    UniverexportLocationClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String sourceUrl
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.sourceUrl = sourceUrl;
    }

    List<UniverexportApiLocation> fetchLocations() {
        JsonNode response = restClient.get()
                .uri(sourceUrl)
                .retrieve()
                .body(JsonNode.class);

        JsonNode rows = response != null && response.isTextual()
                ? objectMapper.readTree(response.asText())
                : response;
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new IllegalStateException(
                    "Zvanični Univerexport API nije vratio lokacije."
            );
        }

        List<UniverexportApiLocation> locations =
                new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            JsonNode value = row.isArray() && row.size() > 1
                    ? row.get(1)
                    : null;
            if (value == null || !value.isObject()) {
                throw new IllegalStateException(
                        "Univerexport lokacija ima neočekivanu strukturu."
                );
            }

            locations.add(new UniverexportApiLocation(
                    text(value, "place_id"),
                    text(value, "sr_name"),
                    text(value, "address"),
                    text(value, "grad"),
                    text(value, "format"),
                    decimal(value, "lat"),
                    decimal(value, "lon"),
                    value.path("status").asInt(0) == 1
            ));
        }

        return List.copyOf(locations);
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText().strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Univerexport lokacija nema polje: " + field
            );
        }
        return value;
    }

    private double decimal(JsonNode node, String field) {
        try {
            double value = Double.parseDouble(text(node, field));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException(field);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Univerexport lokacija ima neispravnu koordinatu: "
                            + field,
                    exception
            );
        }
    }
}
