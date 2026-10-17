package rs.pametnakupovina.backend.product;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two chains sell the same coffee. One publishes today's list, the other has
 * not published since six days ago and is cheaper there. Its price is shown
 * marked as old and after today's, so it is not the best price (V124). Once
 * the same six days turn out to be how late that chain always is, its price
 * is an equal again. The name keeps neither the stock code in front nor the
 * no-break space the file had.
 *
 * A store's own list that stopped coming gives way to the chain's newer list
 * for every shop (V133), as Maxi's Valjevo store files did from 12.10.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class StalePriceListTest {

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

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static final String COFFEE =
            "KAFA;Kafa;369680 KAFA  STAROCENOVNA 200G;Testna;8601234500209;KOM;Test format;%s;;%s;%s;;;20\n";

    private static final String TEA =
            "CAJ;Caj;CAJ PRODAVNICKI 20 KESICA;Testna;8601234500216;KOM;Test format;%s;;%s;%s;;;20\n";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final Map<String, String> catalogues = new ConcurrentHashMap<>();
    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private CanonicalProductDetailsRepository detailsRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/", exchange -> {
            byte[] body = catalogues.getOrDefault(exchange.getRequestURI().getPath(), "")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        csvServer.setExecutor(Executors.newSingleThreadExecutor());
        csvServer.start();
    }

    @AfterAll
    static void stopCsvServer() {
        if (csvServer != null) {
            csvServer.stop(0);
        }
    }

    private long chain(String code, String price, LocalDate listDate) {
        return chain(code, COFFEE, price, listDate);
    }

    private long chain(String code, String row, String price, LocalDate listDate) {
        String path = "/" + code.toLowerCase() + ".csv";
        catalogues.put(path, HEADER + row.formatted(price, listDate.format(DAY), price));
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(code)
                .param(code)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + path)
                .query(Long.class)
                .single();
        assertThat(priceImportService.importPrices(code).status()).isEqualTo("SUCCEEDED");
        return retailerId;
    }

    @Test
    void aListOlderThanItsChainIsLateIsNeverTheBestPrice() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Belgrade"));
        chain("FRESHLIST", "300,00", today);
        long late = chain("LATELIST", "250,00", today.minusDays(6));

        assertThat(jdbcClient.sql("""
                        SELECT DISTINCT name FROM app.retailer_product
                        WHERE barcode = '8601234500209'
                        """)
                .query(String.class)
                .list()).containsExactly("KAFA STAROCENOVNA 200G");
        long product = jdbcClient.sql("""
                        SELECT DISTINCT canonical_product_id FROM app.retailer_product
                        WHERE barcode = '8601234500209'
                        """)
                .query(Long.class)
                .single();

        // One list is no rhythm yet: three days at most.
        assertThat(detailsRepository.findLatestOffers(product, today))
                .extracting(CanonicalProductOffer::retailerCode, CanonicalProductOffer::priceStale)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FRESHLIST", false),
                        org.assertj.core.groups.Tuple.tuple("LATELIST", true)
                );

        // The chain's lists always arrive six days late, a new one each day.
        for (int day = 1; day <= 10; day++) {
            jdbcClient.sql("""
                            INSERT INTO app.import_run (retailer_id, source_url, status, started_at, snapshot_date)
                            VALUES (?, 'test', 'SUCCEEDED', NOW() - make_interval(days => ?), ?)
                            """)
                    .param(late)
                    .param(day)
                    .param(today.minusDays(day + 6L))
                    .update();
        }
        jdbcClient.sql("SELECT app.refresh_price_list_rhythm()")
                .query((resultSet, rowNumber) -> true)
                .single();
        assertThat(jdbcClient.sql("SELECT stale_after_days FROM app.price_list_rhythm WHERE retailer_id = ?")
                .param(late)
                .query(Integer.class)
                .single()).isEqualTo(8);

        assertThat(detailsRepository.findLatestOffers(product, today))
                .extracting(CanonicalProductOffer::retailerCode, CanonicalProductOffer::priceStale)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("LATELIST", false),
                        org.assertj.core.groups.Tuple.tuple("FRESHLIST", false)
                );
    }

    @Test
    void aStoreListBehindTheChainsListForEveryShopNoLongerCounts() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Belgrade"));
        long retailer = chain("STOREFILES", TEA, "300,00", today);
        long format = jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name)
                        VALUES (?, 'STOREFILES', 'Test format')
                        RETURNING id
                        """)
                .param(retailer)
                .query(Long.class)
                .single();
        long store = jdbcClient.sql("""
                        INSERT INTO app.store (retailer_id, store_format_id, external_code, name, address, city, active)
                        VALUES (?, ?, 'STOREFILES-1', 'Objekat', 'Test adresa', 'Valjevo', TRUE)
                        RETURNING id
                        """)
                .param(retailer)
                .param(format)
                .query(Long.class)
                .single();
        // The store's own file, last read six days ago.
        jdbcClient.sql("""
                        INSERT INTO app.current_price_offer (
                            retailer_product_id, import_run_id, scope_type, store_id,
                            price_date, first_seen_date, last_seen_date, regular_price
                        )
                        SELECT offer.retailer_product_id, offer.import_run_id, 'STORE', ?,
                               ?, ?, ?, 250.00
                        FROM app.current_price_offer AS offer
                        JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        WHERE product.retailer_id = ?
                        """)
                .param(store)
                .param(today.minusDays(6))
                .param(today.minusDays(6))
                .param(today.minusDays(6))
                .param(retailer)
                .update();

        assertThat(storeListCounts(retailer, store, today.minusDays(6), today)).isFalse();

        // The store files come again.
        jdbcClient.sql("""
                        UPDATE app.current_price_offer SET price_date = ?, last_seen_date = ?
                        WHERE store_id = ?
                        """)
                .param(today)
                .param(today)
                .param(store)
                .update();
        assertThat(storeListCounts(retailer, store, today, today)).isTrue();
    }

    private boolean storeListCounts(long retailer, long store, LocalDate priceDate, LocalDate asOf) {
        jdbcClient.sql("SELECT app.refresh_price_list_snapshots()")
                .query((resultSet, rowNumber) -> true)
                .single();
        return jdbcClient.sql("SELECT app.in_latest_price_list(?, ?, ?, ?)")
                .param(retailer)
                .param("STORE:" + store)
                .param(priceDate)
                .param(asOf)
                .query(Boolean.class)
                .single();
    }
}
