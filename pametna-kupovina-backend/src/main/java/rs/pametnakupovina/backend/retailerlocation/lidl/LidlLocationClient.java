package rs.pametnakupovina.backend.retailerlocation.lidl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Component
class LidlLocationClient {

    private final RestClient restClient;
    private final String sourceUrl;
    private final String apiKey;

    @Autowired
    LidlLocationClient(
            @Value("${lidl.location-import.url}")
            String sourceUrl,
            @Value("${lidl.location-import.api-key}")
            String apiKey,
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
        this.sourceUrl = sourceUrl;
        this.apiKey = apiKey;
    }

    LidlLocationClient(
            RestClient restClient,
            String sourceUrl,
            String apiKey
    ) {
        this.restClient = restClient;
        this.sourceUrl = sourceUrl;
        this.apiKey = apiKey;
    }

    List<LidlApiLocation> fetchLocations() {
        LidlLocationResponse response = restClient.get()
                .uri(sourceUrl)
                .header("x-apikey", apiKey)
                .retrieve()
                .body(LidlLocationResponse.class);

        if (response == null
                || response.items() == null
                || response.items().isEmpty()) {
            throw new IllegalStateException(
                    "Zvanični Lidl API nije vratio lokacije."
            );
        }

        Integer expectedTotal = response.meta() == null
                ? null
                : response.meta().total();
        if (expectedTotal != null
                && response.items().size() < expectedTotal) {
            throw new IllegalStateException(
                    "Zvanični Lidl API je vratio nepotpunu stranicu: "
                            + response.items().size()
                            + "/"
                            + expectedTotal
            );
        }

        return response.items();
    }
}
