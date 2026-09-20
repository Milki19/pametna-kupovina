package rs.pametnakupovina.backend.priceimport.probe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.priceimport.GovernmentDataResourceDiscoveryClient;
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
    private final GovernmentDataResourceDiscoveryClient discoveryClient;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public PriceListProbeCoordinator(
            GovernmentDatasetCatalogRepository repository,
            PriceListProbeService probeService,
            GovernmentDataResourceDiscoveryClient discoveryClient,
            @Value("${price-import.http.connect-timeout-seconds:20}")
            long connectTimeoutSeconds,
            @Value("${price-import.http.request-timeout-seconds:900}")
            long requestTimeoutSeconds
    ) {
        this.repository = repository;
        this.probeService = probeService;
        this.discoveryClient = discoveryClient;
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

        PublishedFile file = latestPublishedFile(candidate);
        PriceListProbeReport report;

        try {
            report = probe(
                    label,
                    file.url(),
                    file.publishedOn()
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

    /**
     * Most of the portal's own files carry the minute they were replaced in
     * their address, so the address discovery stored days ago is gone by now.
     * The daily import already asks the dataset page for the current one; the
     * probe has to judge that same file, not a file nobody will import.
     */
    private PublishedFile latestPublishedFile(GovernmentDatasetCandidate candidate) {
        LocalDate knownDate = candidate.resourceLastModified() == null
                ? null
                : candidate.resourceLastModified().atZone(ZONE).toLocalDate();

        if (candidate.datasetPageUrl() == null
                || candidate.datasetPageUrl().isBlank()) {
            return new PublishedFile(candidate.resourceUrl(), knownDate);
        }

        try {
            var discovered = discoveryClient.discoverLatestCsv(
                    candidate.datasetPageUrl()
            );

            repository.updateResource(
                    candidate.id(),
                    discovered.url(),
                    discovered.lastModified()
            );

            return new PublishedFile(
                    discovered.url(),
                    discovered.lastModified() == null
                            ? knownDate
                            : discovered.lastModified().atZone(ZONE).toLocalDate()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Ne mogu da pročitam stranicu skupa {}; probam poslednju "
                            + "poznatu adresu cenovnika: {}",
                    candidate.datasetPageUrl(),
                    exception.toString()
            );

            return new PublishedFile(candidate.resourceUrl(), knownDate);
        }
    }

    private record PublishedFile(String url, LocalDate publishedOn) {
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
