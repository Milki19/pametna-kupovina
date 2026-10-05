package rs.pametnakupovina.backend.retailerlocation.univerexport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.regex.Pattern;

@Component
class UniverexportLocationClient {

    private static final Logger log =
            LoggerFactory.getLogger(UniverexportLocationClient.class);
    private static final Pattern SHOP_CODE_PREFIX =
            Pattern.compile("^\\s*(?:[A-Za-z]{1,4}\\d+\\b[\\s,]*)+");
    private static final int MIN_SKIPPED_ALLOWED = 3;
    private static final double MAX_SKIPPED_SHARE = 0.05;

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
        List<String> skipped = new ArrayList<>();
        int activeRows = 0;
        for (JsonNode row : rows) {
            JsonNode value = row.isArray() && row.size() > 1
                    ? row.get(1)
                    : null;
            if (value == null || !value.isObject()) {
                throw new IllegalStateException(
                        "Univerexport lokacija ima neočekivanu strukturu."
                );
            }

            // A closed shop is left out rather than checked: the sync
            // deactivates every shop the source no longer lists, and the
            // source stops filling in a closed shop's address.
            if (value.path("status").asInt(0) != 1) {
                continue;
            }
            activeRows++;

            try {
                locations.add(parse(value));
            } catch (IllegalArgumentException exception) {
                skipped.add(exception.getMessage());
            }
        }

        // One incomplete shop used to fail the whole week's sync. It is now
        // left out, but a source that loses data on many shops at once
        // would deactivate them all, so that still fails.
        if (skipped.size() > Math.max(
                MIN_SKIPPED_ALLOWED,
                activeRows * MAX_SKIPPED_SHARE
        )) {
            throw new IllegalStateException(
                    "Previše neispravnih Univerexport lokacija: "
                            + skipped.size() + " od " + activeRows
                            + ", npr. " + skipped.getFirst()
            );
        }
        skipped.forEach(reason -> log.warn(
                "Preskočena Univerexport lokacija: {}",
                reason
        ));
        if (locations.isEmpty()) {
            throw new IllegalStateException(
                    "Zvanični Univerexport API nije vratio aktivne lokacije."
            );
        }

        return List.copyOf(locations);
    }

    private UniverexportApiLocation parse(JsonNode value) {
        String code = text(value, "place_id");
        String name = text(value, "sr_name");
        try {
            String city = optionalText(value, "grad");
            if (city.isEmpty()) {
                city = cityFromName(name);
            }
            String address = optionalText(value, "address");
            if (address.isEmpty()) {
                address = addressFromName(name, city);
            }
            if (city.isEmpty() || address.isEmpty()) {
                throw new IllegalArgumentException(
                        "Univerexport lokacija nema polje: "
                                + (address.isEmpty() ? "address" : "grad")
                );
            }

            return new UniverexportApiLocation(
                    code,
                    name,
                    address,
                    city,
                    text(value, "format"),
                    decimal(value, "lat"),
                    decimal(value, "lon"),
                    true
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    exception.getMessage() + " (place_id=" + code
                            + ", " + name + ")",
                    exception
            );
        }
    }

    /**
     * The name reads "MP033 Sentandrejski put bb, Novi Sad": the shop code,
     * the street, then the town after the last comma. A name with only the
     * code ("MP161 MP161, Novi Beograd") has no street to give.
     */
    static String addressFromName(String name, String city) {
        String address = SHOP_CODE_PREFIX.matcher(name).replaceFirst("");
        int comma = address.lastIndexOf(',');
        if (comma >= 0 && (city.isEmpty() || address.substring(comma + 1)
                .strip()
                .equalsIgnoreCase(city))) {
            address = address.substring(0, comma);
        }
        address = address.strip();
        return address.equalsIgnoreCase(city) ? "" : address;
    }

    static String cityFromName(String name) {
        int comma = name.lastIndexOf(',');
        return comma < 0 ? "" : name.substring(comma + 1).strip();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull()
                ? value.asText().strip()
                : "";
    }

    private String text(JsonNode node, String field) {
        String value = optionalText(node, field);
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
