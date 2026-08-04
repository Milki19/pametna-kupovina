package rs.pametnakupovina.backend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.priceimport.PriceImportService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PametnaKupovinaBackendApplicationTests {

    private static final String CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            MLEKO;Mlečni proizvodi;Mleko 1 l;Test brend;8600000000001;l;Test format;150;;01-03-2026;150;;;20
            MLEKO;Mlečni proizvodi;Mleko 1 l;Test brend;8600000000001;l;Test format;160;;02-03-2026;160;;;20
            HLEB;Pekarski proizvodi;Beli hleb;Test pekara;8600000000002;kom;Test format;80;;02-03-2026;80;;;20
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        csvServer.createContext("/prices.csv", exchange -> {
            byte[] responseBody =
                    CSV_CONTENT.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/csv; charset=UTF-8"
            );

            exchange.sendResponseHeaders(
                    200,
                    responseBody.length
            );

            try (OutputStream outputStream =
                         exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        });

        csvServer.start();
    }

    @AfterAll
    static void stopCsvServer() {
        if (csvServer != null) {
            csvServer.stop(0);
        }
    }

    @Test
    void repeatedImportUpdatesPricesWithoutCreatingDuplicates() {
        String datasetUrl =
                "http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/prices.csv";

        jdbcClient.sql("""
                        INSERT INTO app.retailer (
                            code,
                            name,
                            dataset_url
                        )
                        VALUES (?, ?, ?)
                        """)
                .param(1, "TEST")
                .param(2, "Test prodavnica")
                .param(3, datasetUrl)
                .update();

        priceImportService.importPrices("TEST", 100);

        Long firstImportRunId = latestImportRunId();
        Long countAfterFirstImport = priceObservationCount();

        priceImportService.importPrices("TEST", 100);

        Long secondImportRunId = latestImportRunId();
        Long countAfterSecondImport = priceObservationCount();

        Long rowsPointingToFirstImport = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.price_observation
                        WHERE import_run_id = ?
                        """)
                .param(1, firstImportRunId)
                .query(Long.class)
                .single();

        Long rowsPointingToSecondImport = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.price_observation
                        WHERE import_run_id = ?
                        """)
                .param(1, secondImportRunId)
                .query(Long.class)
                .single();

        List<String> importStatuses = jdbcClient.sql("""
                        SELECT status
                        FROM app.import_run
                        ORDER BY id
                        """)
                .query(String.class)
                .list();

        assertThat(countAfterFirstImport).isEqualTo(2);
        assertThat(countAfterSecondImport).isEqualTo(2);

        assertThat(secondImportRunId)
                .isGreaterThan(firstImportRunId);

        assertThat(rowsPointingToFirstImport).isZero();
        assertThat(rowsPointingToSecondImport).isEqualTo(2);

        assertThat(importStatuses)
                .containsExactly("SUCCEEDED", "SUCCEEDED");
    }

    private Long latestImportRunId() {
        return jdbcClient.sql("""
                        SELECT MAX(id)
                        FROM app.import_run
                        """)
                .query(Long.class)
                .single();
    }

    private Long priceObservationCount() {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.price_observation
                        """)
                .query(Long.class)
                .single();
    }
}