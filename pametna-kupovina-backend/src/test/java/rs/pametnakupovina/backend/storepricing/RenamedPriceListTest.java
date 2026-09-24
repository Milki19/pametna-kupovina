package rs.pametnakupovina.backend.storepricing;

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
import rs.pametnakupovina.backend.shoppinglist.StoreShoppingOfferRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chains rename their price lists. Super Vero turned its one list
 * "Veropoulos d.o.o. OJ1" into "Veropoulos d.o.o.", and its shop has to follow
 * instead of dropping out of every plan. Univerexport turned five zone lists
 * into "L", "M" and "S": nothing tells which list a shop quotes, so no shop is
 * linked, but the new lists are shown without an address like the old ones.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class RenamedPriceListTest {

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

    private static final Map<String, String> SERVED = new ConcurrentHashMap<>();

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/", exchange -> {
            byte[] body = SERVED.getOrDefault(exchange.getRequestURI().getPath(), "")
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

    private static String priceList(String date, String... lists) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String list : lists) {
            csv.append("1;Mleko;MLEKO 2,8% 1L;Testna;8600000003011;KOM;").append(list)
                    .append(";129,99;;").append(date).append(";129,99;;;10\n");
            csv.append("5;Hleb;HLEB BELI 500G;Testna;8600000003028;KOM;").append(list)
                    .append(";69,99;;").append(date).append(";139,98;;;10\n");
        }
        return csv.toString();
    }

    private long chain(String code) {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(code)
                .param(code)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/" + code + ".csv")
                .query(Long.class)
                .single();
        // Shown without an address, like METRO, Univerexport and Super Vero (V65).
        format(retailerId, code + "_PRICE_LIST");
        return retailerId;
    }

    private long format(long retailerId, String code) {
        return jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(code)
                .param(code)
                .query(Long.class)
                .single();
    }

    private void placedShop(long retailerId, String code, String list) {
        long formatId = format(retailerId, code + "_SHOP");
        jdbcClient.sql("""
                        WITH coordinates AS (
                            SELECT ST_SetSRID(ST_MakePoint(20.42, 44.80), 4326)::geography AS location
                        )
                        INSERT INTO app.store (
                            retailer_id, store_format_id, external_code, name, address, city, location,
                            active, geocoding_candidate, geocoding_status, geocoding_query, geocoding_source,
                            geocoding_matched_address, geocoding_confidence, geocoded_at, pricing_eligible,
                            pricing_ineligibility_reason
                        )
                        SELECT ?, ?, ?, ?, 'Test adresa', 'Beograd', coordinates.location,
                               TRUE, coordinates.location, 'MANUALLY_VERIFIED', 'test adresa, beograd',
                               'RENAMED_LIST_TEST', 'Test adresa, Beograd', 1.0000, NOW(), FALSE,
                               'PRICE_FORMAT_NOT_VERIFIED'
                        FROM coordinates
                        """)
                .param(retailerId)
                .param(formatId)
                .param(code + "_1")
                .param(code + " 1")
                .update();
        jdbcClient.sql("""
                        INSERT INTO app.store_price_format_mapping (
                            retailer_id, source_store_code, store_external_code, retailer_format_name,
                            verification_status, mapping_method, source_url
                        )
                        VALUES (?, ?, ?, ?, 'VERIFIED', 'PUBLISHED_PRICE_LIST_PER_STORE', 'https://example.test/shops')
                        """)
                .param(retailerId)
                .param(code + "_1")
                .param(code + "_1")
                .param(list)
                .update();
    }

    private void importDay(String code, String csv) {
        SERVED.put("/" + code + ".csv", csv);
        assertThat(priceImportService.importPrices(code).status()).startsWith("SUCCEEDED");
    }

    private Map<String, Object> shop(String externalCode) {
        return jdbcClient.sql("""
                        SELECT store.pricing_eligible,
                               mapping.retailer_format_name,
                               mapping.mapping_method
                        FROM app.store AS store
                        LEFT JOIN app.store_price_format_mapping AS mapping
                          ON mapping.retailer_id = store.retailer_id
                         AND mapping.store_external_code = store.external_code
                         AND mapping.active = TRUE
                        WHERE store.external_code = ?
                        """)
                .param(externalCode)
                .query()
                .singleRow();
    }

    private List<String> listsWithoutAddress(String retailerCode) {
        return offerRepository.findPriceListEntriesWithoutLocation()
                .stream()
                .filter(entry -> entry.retailerCode().equals(retailerCode))
                .map(StoreShoppingOfferRepository.PriceListEntry::label)
                .toList();
    }

    @Test
    void shopsAndListsWithoutAnAddressFollowRenamedPriceLists() {
        long single = chain("SINGLE");
        long zones = chain("ZONES");
        placedShop(single, "SINGLE", "Single d.o.o. OJ1");
        placedShop(zones, "ZONES", "ZONES - C0");

        importDay("SINGLE", priceList("13-09-2026", "Single d.o.o. OJ1"));
        importDay("ZONES", priceList("13-09-2026", "ZONES - C0", "ZONES - C1"));

        assertThat(shop("SINGLE_1").get("pricing_eligible")).isEqualTo(true);
        assertThat(shop("ZONES_1").get("pricing_eligible")).isEqualTo(true);
        // A list a placed shop quotes is not shown twice.
        assertThat(listsWithoutAddress("SINGLE")).isEmpty();
        assertThat(listsWithoutAddress("ZONES")).containsExactly("ZONES - C1");

        importDay("SINGLE", priceList("14-09-2026", "Single d.o.o."));
        importDay("ZONES", priceList("14-09-2026", "ZONES - L", "ZONES - M"));

        // The one list was renamed: the shop follows it.
        assertThat(shop("SINGLE_1"))
                .containsEntry("pricing_eligible", true)
                .containsEntry("retailer_format_name", "Single d.o.o.")
                .containsEntry("mapping_method", "SINGLE_PRICE_LIST_RENAMED");
        assertThat(listsWithoutAddress("SINGLE")).isEmpty();

        // Two new lists: no shop is guessed onto one, both are shown without
        // an address and the retired zones are gone.
        assertThat(shop("ZONES_1").get("pricing_eligible")).isEqualTo(false);
        assertThat(listsWithoutAddress("ZONES")).containsExactlyInAnyOrder("ZONES - L", "ZONES - M");
    }

    /**
     * Cash & Carry Plus Kula, Euro Ša M i Matijević (23.09.): naziv
     * cenovnika duži od 50 znakova rušio je ceo uvoz lanca.
     */
    @Test
    void aLongPriceListNameIsStillShownWithoutAnAddress() {
        chain("LONG");
        String list = "Maloprodajni objekat broj 12 - Cash & Carry Plus doo Kula, Lenjinova 3";

        importDay("LONG", priceList("13-09-2026", list));

        assertThat(listsWithoutAddress("LONG")).containsExactly(list);
    }
}
