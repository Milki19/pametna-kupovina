package rs.pametnakupovina.backend.priceimport.probe;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.priceimport.GovernmentDatasetCandidate;
import rs.pametnakupovina.backend.priceimport.GovernmentDatasetCatalogRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The probe reads a published file over HTTP and records what it found. */
class PriceListProbeCoordinatorTest {

    private final GovernmentDatasetCatalogRepository repository =
            mock(GovernmentDatasetCatalogRepository.class);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsAPublishedFileAndRecordsTheVerdict() throws IOException {
        String csv = "Naziv proizvoda;Barkod proizvoda;Jedinica mere;"
                + "Naziv trgovca - formata;Datum cenovnika;Redovna cena\n"
                + IntStream.range(0, 150)
                .mapToObj(index -> "Mleko " + index + ";860000000000" + (index % 10)
                        + ";KOM;Aman;" + LocalDate.now() + ";99,99\n")
                .collect(Collectors.joining());

        PriceListProbeReport report = probeServing(csv);

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.READY);
        assertThat(report.rowsRead()).isEqualTo(150);
        verify(repository).saveProbe(eq(7L), eq("READY"), contains("150 redova"));
    }

    @Test
    void aFileThatCannotBeDownloadedIsRejectedInsteadOfFailing() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/cenovnik.csv", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        PriceListProbeReport report = coordinator(url()).probe(7L);

        assertThat(report.verdict()).isEqualTo(PriceListProbeVerdict.REJECTED);
        assertThat(report.findings().getFirst()).contains("Preuzimanje nije uspelo");
        verify(repository).saveProbe(anyLong(), eq("REJECTED"), anyString());
    }

    private PriceListProbeReport probeServing(String csv) throws IOException {
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/cenovnik.csv", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/csv");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        return coordinator(url()).probe(7L);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/cenovnik.csv";
    }

    private PriceListProbeCoordinator coordinator(String url) {
        when(repository.findById(7L)).thenReturn(new GovernmentDatasetCandidate(
                7L, "dataset-7", "aman", "Cenovnici Aman", "Aman d.o.o.",
                "https://data.gov.rs/aman", "resource-7", "cene-aman.csv", url,
                "CSV", null, "DISCOVERED", null, null, null, null, null
        ));

        return new PriceListProbeCoordinator(
                repository, new PriceListProbeService(), 5, 20
        );
    }
}
