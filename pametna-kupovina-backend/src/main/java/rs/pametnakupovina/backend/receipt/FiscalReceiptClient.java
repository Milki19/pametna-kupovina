package rs.pametnakupovina.backend.receipt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Isti link koji stoji u QR kodu vraća i JSON, ako se traži. U njemu stavke
 * ne stoje kao podaci nego kao otkucan račun (`journal`), pa se odatle i čita.
 */
@Component
public class FiscalReceiptClient {

    private final RestClient restClient;

    public FiscalReceiptClient(
            @Value("${receipt.fetch-timeout-seconds:20}") long timeoutSeconds
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
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
    }

    /**
     * @return otkucan račun, ili prazno kad Poreska uprava ne odgovori —
     *         račun je već zaveden iz QR koda i bez stavki vredi
     */
    public Optional<String> journalOf(String verificationUrl) {
        try {
            JsonNode response = restClient.get()
                    .uri(verificationUrl)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("isValid").asBoolean(false)) {
                return Optional.empty();
            }

            String journal = response.path("journal").asText("");

            return journal.isBlank() ? Optional.empty() : Optional.of(journal);
        } catch (RuntimeException unreachable) {
            return Optional.empty();
        }
    }
}
