package rs.pametnakupovina.backend.place;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.market.MarketRepository;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Karaburma, Beograd" as a point to start the trip from, for the web app,
 * which has no phone geocoder like Android. OpenStreetMap's Nominatim answers;
 * the server asks on the shopper's behalf, so the address leaves without the
 * shopper's network address, and it is neither logged nor stored.
 *
 * <p>Nominatim's rules allow one request a second from one application, so
 * requests wait their turn and repeated searches come from a small cache.
 */
@Service
public class PlaceSearchService {

    static final int MAX_RESULTS = 5;
    private static final int CACHE_SIZE = 500;

    private final RestClient restClient;
    private final MarketRepository markets;
    private final String searchUrl;
    private final long minimumGapMillis;
    private final Map<String, List<Place>> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Place>> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private long lastRequestAt;

    public PlaceSearchService(
            MarketRepository markets,
            @Value("${place-search.url:https://nominatim.openstreetmap.org/search}") String searchUrl,
            @Value("${place-search.user-agent:PametnaKupovina/2.0 (https://pametna-kupovina.duckdns.org)}") String userAgent,
            @Value("${place-search.minimum-gap-ms:1100}") long minimumGapMillis,
            @Value("${place-search.timeout-seconds:8}") long timeoutSeconds
    ) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", userAgent)
                .build();
        this.markets = markets;
        this.searchUrl = searchUrl;
        this.minimumGapMillis = minimumGapMillis;
    }

    public List<Place> search(long accountId, String query) {
        String text = query == null ? "" : query.strip().replaceAll("\\s+", " ");

        if (text.length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Upiši bar tri slova adrese ili kraja.");
        }
        if (text.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Adresa može imati najviše 200 karaktera.");
        }

        Market market = markets.forAccount(accountId);
        String key = market.code() + "|" + text.toLowerCase(Locale.ROOT);

        synchronized (cache) {
            List<Place> known = cache.get(key);
            if (known != null) {
                return known;
            }
        }

        List<Place> places = ask(text, market);

        synchronized (cache) {
            cache.put(key, places);
        }
        return places;
    }

    private List<Place> ask(String text, Market market) {
        waitForTurn();

        JsonNode answer;
        try {
            answer = restClient.get()
                    .uri(searchUrl, builder -> builder
                            .queryParam("q", text)
                            .queryParam("format", "jsonv2")
                            .queryParam("limit", MAX_RESULTS)
                            .queryParam("countrycodes", market.code().toLowerCase(Locale.ROOT))
                            .queryParam("accept-language", market.defaultLanguage())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Pretraga adrese trenutno ne radi. Probaj ponovo za koji trenutak.");
        }

        List<Place> places = new ArrayList<>();
        if (answer != null && answer.isArray()) {
            for (JsonNode node : answer) {
                double latitude = node.path("lat").asDouble(Double.NaN);
                double longitude = node.path("lon").asDouble(Double.NaN);
                String name = node.path("display_name").asText("").strip();
                if (!Double.isNaN(latitude) && !Double.isNaN(longitude) && !name.isEmpty()) {
                    places.add(new Place(name, latitude, longitude));
                }
            }
        }
        return List.copyOf(places);
    }

    private void waitForTurn() {
        synchronized (this) {
            long wait = lastRequestAt + minimumGapMillis - System.currentTimeMillis();
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Pretraga adrese trenutno ne radi. Probaj ponovo za koji trenutak.");
                }
            }
            lastRequestAt = System.currentTimeMillis();
        }
    }

    /** @param name the address as the map writes it, to show the shopper */
    public record Place(String name, double latitude, double longitude) {
    }
}
