package rs.pametnakupovina.backend.retailerlocation.dis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Component
class DisLocationClient {

    private final RestClient restClient;
    private final String sourceUrl;

    @Autowired
    DisLocationClient(
            @Value("${dis.location-import.url}")
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
        requestFactory.setReadTimeout(
                Duration.ofSeconds(requestTimeoutSeconds)
        );

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
        this.sourceUrl = sourceUrl;
    }

    DisLocationClient(RestClient restClient, String sourceUrl) {
        this.restClient = restClient;
        this.sourceUrl = sourceUrl;
    }

    List<DisApiLocation> fetchLocations() {
        DisApiLocation[] response = restClient.get()
                .uri(sourceUrl)
                .retrieve()
                .body(DisApiLocation[].class);

        if (response == null || response.length == 0) {
            throw new IllegalStateException(
                    "Zvanični DIS API nije vratio lokacije."
            );
        }

        return Arrays.asList(response);
    }
}
