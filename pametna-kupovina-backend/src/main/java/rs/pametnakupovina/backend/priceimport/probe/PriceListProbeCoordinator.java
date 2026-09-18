package rs.pametnakupovina.backend.priceimport.probe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.priceimport.GovernmentDatasetCandidate;
import rs.pametnakupovina.backend.priceimport.GovernmentDatasetCatalogRepository;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Reads one discovered chain's published file and records what it found. The
 * chain itself is not registered here: the probe only decides whether it is
 * worth registering, and says what a person would have to sort out first.
 */
@Service
public class PriceListProbeCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(PriceListProbeCoordinator.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");

    private final GovernmentDatasetCatalogRepository repository;
    private final PriceListProbeService probeService;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public PriceListProbeCoordinator(
            GovernmentDatasetCatalogRepository repository,
            PriceListProbeService probeService,
            @Value("${price-import.http.connect-timeout-seconds:20}")
            long connectTimeoutSeconds,
            @Value("${price-import.http.request-timeout-seconds:900}")
            long requestTimeoutSeconds
    ) {
        this.repository = repository;
        this.probeService = probeService;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public PriceListProbeReport probe(long candidateId) {
        GovernmentDatasetCandidate candidate = repository.findById(candidateId);
        String label = candidate.organizationName() == null
                ? candidate.title()
                : candidate.organizationName();

        PriceListProbeReport report;

        try {
            report = probe(
                    label,
                    candidate.resourceUrl(),
                    candidate.resourceLastModified() == null
                            ? null
                            : candidate.resourceLastModified().atZone(ZONE).toLocalDate()
            );
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            log.warn(
                    "Probno čitanje cenovnika nije uspelo za {}: {}",
                    label,
                    exception.toString()
            );

            report = new PriceListProbeReport(
                    label, 0, 0, 0, 0, List.of(), null, null, List.of(),
                    List.of("Preuzimanje nije uspelo: " + exception.getMessage()),
                    PriceListProbeVerdict.REJECTED
            );
        }

        repository.saveProbe(
                candidateId,
                report.verdict().name(),
                summary(report)
        );

        return report;
    }

    private PriceListProbeReport probe(String label, String url, LocalDate publishedOn)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", "PametnaKupovina/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }

            // The probe stops after enough rows, so a 90 MB file is never
            // downloaded whole just to be judged.
            return probeService.probe(label, body, LocalDate.now(ZONE), publishedOn);
        }
    }

    /** One line for the registry, the details stay in the returned report. */
    private String summary(PriceListProbeReport report) {
        StringBuilder summary = new StringBuilder()
                .append(report.rowsRead()).append(" redova, ")
                .append("upotrebljivo ").append(report.usableShare()).append("%, ")
                .append("barkod ").append(report.barcodeShare()).append("%, ")
                .append("cenovnika ").append(report.priceListNames().size());

        if (report.newestPriceDate() != null) {
            summary.append(", datum ").append(report.newestPriceDate());
        }

        if (!report.findings().isEmpty()) {
            summary.append(". ").append(String.join(" ", report.findings()));
        }

        return summary.toString();
    }
}
