package rs.pametnakupovina.backend.receipt;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Isti link koji stoji u QR kodu vraća i JSON, ako se traži. U njemu stavke
 * ne stoje kao podaci nego kao otkucan račun (`journal`), pa se odatle i čita.
 */
@Component
public class FiscalReceiptClient {

    private static final Logger log =
            LoggerFactory.getLogger(FiscalReceiptClient.class);

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
     * @return šta Poreska uprava ima o računu, ili prazno kad ne odgovori —
     *         račun je već zaveden iz QR koda i bez toga vredi
     */
    public Optional<FetchedReceipt> fetch(String verificationUrl) {
        try {
            // Adresa se predaje kao gotov URI, ne kao šablon: u kodu sa računa
            // stoje %2F i %2B, a šablon bi ih prekodirao i Poreska uprava bi
            // vratila 400.
            JsonNode response = restClient.get()
                    .uri(URI.create(verificationUrl))
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                log.warn("Poreska uprava nije vratila ništa za račun.");
                return Optional.empty();
            }

            if (!response.path("isValid").asBoolean(false)) {
                log.warn("Poreska uprava kaže da račun nije ispravan.");
                return Optional.empty();
            }

            String journal = response.path("journal").asText("");

            if (journal.isBlank()) {
                log.warn("Odgovor Poreske uprave nema otkucan račun.");
                return Optional.empty();
            }

            JsonNode request = response.path("invoiceRequest");

            return Optional.of(new FetchedReceipt(
                    journal,
                    shopName(request),
                    blankToNull(request.path("taxId").asText(""))
            ));
        } catch (RuntimeException unreachable) {
            // Ćutke se gubila i sama greška, pa se nije videlo ni da je bilo
            // pokušaja. Račun i dalje ostaje zaveden bez stavki.
            log.warn(
                    "Čitanje računa sa Poreske uprave nije uspelo: {}",
                    unreachable.toString()
            );
            return Optional.empty();
        }
    }

    /**
     * Objekat („1207756-PLEASURE PARK ČAIR") znači kupcu više od firme, jer je
     * to prodavnica u koju je ušao; broj ispred njega ne znači nikome ništa.
     */
    private static String shopName(JsonNode request) {
        String location = request.path("locationName").asText("").strip();

        if (!location.isEmpty()) {
            int dash = location.indexOf('-');

            if (dash > 0 && location.substring(0, dash).chars()
                    .allMatch(Character::isDigit)) {
                return location.substring(dash + 1).strip();
            }

            return location;
        }

        return blankToNull(request.path("businessName").asText(""));
    }

    private static String blankToNull(String value) {
        String text = value == null ? "" : value.strip();

        return text.isEmpty() ? null : text;
    }

    /**
     * @param shopName prodavnica kako je Poreska uprava vodi; pouzdanija od
     *                 one iz QR koda, koje na pola računa i nema
     */
    public record FetchedReceipt(
            String journal,
            String shopName,
            String taxIdentificationNumber
    ) {
    }
}
