package rs.pametnakupovina.backend.retailerlocation.idearoda;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class IdeaRodaLocationClient {

    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script[^>]*id=\\\"__NEXT_DATA__\\\"[^>]*>"
                    + "(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String ideaSourceUrl;
    private final String rodaSourceUrl;

    @Autowired
    IdeaRodaLocationClient(
            @Value("${idea.location-import.url}")
            String ideaSourceUrl,
            @Value("${roda.location-import.url}")
            String rodaSourceUrl,
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
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
        this.objectMapper = objectMapper;
        this.ideaSourceUrl = ideaSourceUrl;
        this.rodaSourceUrl = rodaSourceUrl;
    }

    IdeaRodaLocationClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String ideaSourceUrl,
            String rodaSourceUrl
    ) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.ideaSourceUrl = ideaSourceUrl;
        this.rodaSourceUrl = rodaSourceUrl;
    }

    List<IdeaRodaApiLocation> fetchLocations() {
        List<IdeaRodaApiLocation> locations = new ArrayList<>();
        locations.addAll(fetchIdeaLocations());
        locations.addAll(fetchRodaLocations());
        if (locations.isEmpty()) {
            throw new IllegalStateException(
                    "Zvanični IDEA/Roda lokatori nisu vratili lokacije."
            );
        }
        return List.copyOf(locations);
    }

    private List<IdeaRodaApiLocation> fetchIdeaLocations() {
        JsonNode response = restClient.get()
                .uri(ideaSourceUrl)
                .accept(org.springframework.http.MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class);
        JsonNode markers = response == null ? null : response.path("markers");
        if (response == null
                || !response.path("success").asBoolean(false)
                || markers == null
                || !markers.isArray()
                || markers.isEmpty()) {
            throw new IllegalStateException(
                    "Zvanični IDEA lokator nije vratio lokacije."
            );
        }

        List<IdeaRodaApiLocation> locations =
                new ArrayList<>(markers.size());
        for (JsonNode marker : markers) {
            String latitude = text(marker, "lat", "IDEA");
            String longitude = text(marker, "lng", "IDEA");
            String type = text(marker, "type", "IDEA");
            String address = text(marker, "address", "IDEA");
            locations.add(new IdeaRodaApiLocation(
                    "IDEA",
                    geoCode(latitude, longitude),
                    type + " " + address,
                    address,
                    "Srbija",
                    type,
                    parseCoordinate(latitude, "lat", "IDEA"),
                    parseCoordinate(longitude, "lng", "IDEA")
            ));
        }
        return locations;
    }

    private List<IdeaRodaApiLocation> fetchRodaLocations() {
        String html = restClient.get()
                .uri(rodaSourceUrl)
                .accept(org.springframework.http.MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);
        Matcher matcher = NEXT_DATA.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Zvanični Roda lokator nema očekivani JSON odgovor."
            );
        }

        JsonNode stores = objectMapper.readTree(matcher.group(1))
                .path("props")
                .path("pageProps")
                .path("page")
                .path("attributes")
                .path("blocks")
                .path(0)
                .path("stores");
        if (!stores.isArray() || stores.isEmpty()) {
            throw new IllegalStateException(
                    "Zvanični Roda lokator nije vratio lokacije."
            );
        }

        List<IdeaRodaApiLocation> locations =
                new ArrayList<>(stores.size());
        for (JsonNode wrapper : stores) {
            JsonNode store = wrapper.path("item");
            String code = text(store, "title", "Roda");
            String type = store.path("storeType").asText().strip();
            if (type.isEmpty()) {
                // Stranica je isključivo Roda lokator. Jedan postojeći
                // zapis (417) nema podtip, pa je osnovni RODA format
                // jedini izvorno potvrđen i bezbedan fallback.
                type = "RODA";
            }
            locations.add(new IdeaRodaApiLocation(
                    "RODA",
                    "RODA_" + code,
                    type + " " + code,
                    text(store, "address", "Roda"),
                    text(store, "city", "Roda"),
                    type,
                    number(store, "latitude", "Roda"),
                    number(store, "longitude", "Roda")
            ));
        }
        return locations;
    }

    private String geoCode(String latitude, String longitude) {
        return "IDEA_GEO_"
                + keyPart(latitude)
                + "_"
                + keyPart(longitude);
    }

    private String keyPart(String coordinate) {
        return coordinate.strip()
                .replace("-", "M")
                .replace('.', '_');
    }

    private String text(JsonNode node, String field, String retailer) {
        String value = node.path(field).asText().strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    retailer + " lokacija nema polje: " + field
            );
        }
        return value;
    }

    private double number(JsonNode node, String field, String retailer) {
        JsonNode value = node.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
            throw new IllegalArgumentException(
                    retailer + " lokacija ima neispravnu koordinatu: "
                            + field
            );
        }
        return value.asDouble();
    }

    private double parseCoordinate(
            String value,
            String field,
            String retailer
    ) {
        try {
            double coordinate = Double.parseDouble(value);
            if (!Double.isFinite(coordinate)) {
                throw new NumberFormatException(field);
            }
            return coordinate;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    retailer + " lokacija ima neispravnu koordinatu: "
                            + field,
                    exception
            );
        }
    }
}
