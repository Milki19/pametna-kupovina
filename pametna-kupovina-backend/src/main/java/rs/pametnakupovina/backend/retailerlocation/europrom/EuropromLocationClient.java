package rs.pametnakupovina.backend.retailerlocation.europrom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class EuropromLocationClient {

    private static final int MINIMUM_EXPECTED_LOCATIONS = 40;
    private static final Pattern STORE_HEADING = Pattern.compile(
            "w-separator-text><span>([^<]+)</span>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EUROCENTER_HEADING = Pattern.compile(
            "<h3>\\s*Еуроцентар\\s*</h3>",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ADDRESS = Pattern.compile(
            "fa-map-marker-alt.*?class=w-iconbox-title>([^<]+)<",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern COORDINATES = Pattern.compile(
            "!2d(-?\\d+(?:\\.\\d+)?)!3d(-?\\d+(?:\\.\\d+)?)"
    );

    private final RestClient restClient;
    private final String sourceUrl;

    @Autowired
    EuropromLocationClient(
            @Value("${europrom.location-import.url}")
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
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
        this.sourceUrl = sourceUrl;
    }

    EuropromLocationClient(RestClient restClient, String sourceUrl) {
        this.restClient = restClient;
        this.sourceUrl = sourceUrl;
    }

    List<EuropromPageLocation> fetchLocations() {
        byte[] responseBody = restClient.get()
                .uri(sourceUrl)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(byte[].class);
        String html = responseBody == null
                ? ""
                : new String(responseBody, StandardCharsets.UTF_8);
        List<EuropromPageLocation> locations = parseLocations(
                html
        );

        if (locations.size() < MINIMUM_EXPECTED_LOCATIONS) {
            throw new IllegalStateException(
                    "Zvanična Europrom stranica vratila je samo "
                            + locations.size()
                            + " lokacija; import je zaustavljen da postojeći "
                            + "objekti ne bi bili deaktivirani."
            );
        }

        return locations;
    }

    List<EuropromPageLocation> parseLocations(String html) {
        Map<String, EuropromPageLocation> locations = new LinkedHashMap<>();
        addEurocenter(html, locations);

        List<Heading> headings = new ArrayList<>();
        Matcher headingMatcher = STORE_HEADING.matcher(html);
        while (headingMatcher.find()) {
            headings.add(new Heading(
                    cleanText(headingMatcher.group(1)),
                    headingMatcher.start(),
                    headingMatcher.end()
            ));
        }

        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int blockEnd = index + 1 < headings.size()
                    ? headings.get(index + 1).start()
                    : html.length();
            addLocation(
                    heading.name(),
                    html.substring(heading.end(), blockEnd),
                    locations
            );
        }

        return List.copyOf(locations.values());
    }

    private void addEurocenter(
            String html,
            Map<String, EuropromPageLocation> locations
    ) {
        Matcher heading = EUROCENTER_HEADING.matcher(html);
        if (!heading.find()) {
            return;
        }

        Coordinates coordinates = lastCoordinatesBefore(
                html,
                heading.start()
        );
        Matcher address = ADDRESS.matcher(html.substring(heading.end()));

        if (coordinates == null || !address.find()) {
            throw new IllegalStateException(
                    "Europrom Eurocentar nema očekivanu adresu ili koordinate."
            );
        }

        putUnique(
                locations,
                location(
                        "Еуроцентар",
                        cleanText(address.group(1)),
                        coordinates
                )
        );
    }

    private void addLocation(
            String name,
            String block,
            Map<String, EuropromPageLocation> locations
    ) {
        Matcher address = ADDRESS.matcher(block);
        Matcher coordinates = COORDINATES.matcher(block);

        if (!address.find() || !coordinates.find()) {
            throw new IllegalStateException(
                    "Europrom lokacija nema očekivanu adresu ili koordinate: "
                            + name
            );
        }

        putUnique(
                locations,
                location(
                        name,
                        cleanText(address.group(1)),
                        new Coordinates(
                                Double.parseDouble(coordinates.group(2)),
                                Double.parseDouble(coordinates.group(1))
                        )
                )
        );
    }

    private EuropromPageLocation location(
            String name,
            String address,
            Coordinates coordinates
    ) {
        return new EuropromPageLocation(
                name,
                address,
                inferCity(name, address),
                coordinates.latitude(),
                coordinates.longitude()
        );
    }

    private Coordinates lastCoordinatesBefore(String html, int end) {
        Matcher matcher = COORDINATES.matcher(html.substring(0, end));
        Coordinates result = null;

        while (matcher.find()) {
            result = new Coordinates(
                    Double.parseDouble(matcher.group(2)),
                    Double.parseDouble(matcher.group(1))
            );
        }

        return result;
    }

    private void putUnique(
            Map<String, EuropromPageLocation> locations,
            EuropromPageLocation location
    ) {
        String key = location.name().toUpperCase(Locale.ROOT);
        if (locations.putIfAbsent(key, location) != null) {
            throw new IllegalStateException(
                    "Duplirana Europrom lokacija na zvaničnoj stranici: "
                            + location.name()
            );
        }
    }

    private String inferCity(String name, String address) {
        int separator = address.lastIndexOf(',');
        if (separator >= 0 && separator < address.length() - 1) {
            return address.substring(separator + 1).strip();
        }

        if (address.startsWith("Село ")) {
            return address.substring("Село ".length())
                    .replaceFirst("(?i)\\s+бб$", "")
                    .strip();
        }

        return name;
    }

    private String cleanText(String value) {
        return value
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&#038;", "&")
                .replace("&#8211;", "–")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private record Heading(String name, int start, int end) {
    }

    private record Coordinates(double latitude, double longitude) {
    }
}
