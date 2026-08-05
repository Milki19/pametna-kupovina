package rs.pametnakupovina.backend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.geocoding.StoreGeocodingCandidateRequest;
import rs.pametnakupovina.backend.geocoding.StoreGeocodingResult;
import rs.pametnakupovina.backend.geocoding.StoreGeocodingReviewRequest;
import rs.pametnakupovina.backend.geocoding.StoreGeocodingService;
import rs.pametnakupovina.backend.geocoding.StoreGeocodingStatus;
import rs.pametnakupovina.backend.matching.FuzzyProductCandidate;
import rs.pametnakupovina.backend.matching.FuzzyProductCandidateService;
import rs.pametnakupovina.backend.matching.ProductMatchDecision;
import rs.pametnakupovina.backend.matching.ProductMatchDecisionSource;
import rs.pametnakupovina.backend.matching.ProductMatchDecisionService;
import rs.pametnakupovina.backend.matching.ProductMatchFeedback;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackAction;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackRequest;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackService;
import rs.pametnakupovina.backend.matching.ProductMatchStatus;
import rs.pametnakupovina.backend.priceimport.PriceImportService;
import rs.pametnakupovina.backend.product.CanonicalProductSearchPage;
import rs.pametnakupovina.backend.product.CanonicalProductSearchService;
import rs.pametnakupovina.backend.product.ProductSearchResult;
import rs.pametnakupovina.backend.product.ProductSearchService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.shoppinglist.AddShoppingListItemRequest;
import rs.pametnakupovina.backend.shoppinglist.CreateShoppingListRequest;
import rs.pametnakupovina.backend.shoppinglist.PasteShoppingListItemsRequest;
import rs.pametnakupovina.backend.shoppinglist.PasteShoppingListItemsResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemMatchingStatus;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemRule;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListItemResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListService;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListSummary;
import rs.pametnakupovina.backend.shoppinglist.UpdateShoppingListRequest;
import rs.pametnakupovina.backend.store.NearbyStore;
import rs.pametnakupovina.backend.store.NearbyStoreService;
import rs.pametnakupovina.backend.store.Store;
import rs.pametnakupovina.backend.store.StoreFormat;
import rs.pametnakupovina.backend.store.StoreRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PametnaKupovinaBackendApplicationTests {

    private static final String CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            MLEKO;Mlečni proizvodi;Mleko 1 l;Test brend;8600000000004;l;Test format;150;;01-03-2026;150;;;20
            MLEKO;Mlečni proizvodi;Mleko 1 l;Test brend;8600000000004;l;Test format;160;;02-03-2026;160;;;20
            HLEB;Pekarski proizvodi;Beli hleb;Test pekara;8600000000011;kom;Test format;80;;02-03-2026;80;;;20
            """;

    private static final String EXACT_EAN_A_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            SOK;Sokovi;Sok od narandže 1 l;Test sok;8601234567899;l;Format A;210;;04-08-2026;210;;;20
            """;

    private static final String EXACT_EAN_B_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            NAPICI;Bezalkoholna pića;Pomorandža sok 1000 ml;Test sok;8601234567899;ml;Format B;205;;04-08-2026;205;;;20
            """;

    private static final String RETAILER_LOCATION_CSV_CONTENT = """
            external_code;name;address;city;latitude;longitude;active
            PK037-IMPORT-001;Test objekat;Test adresa 1;Beograd;44.8176;20.4569;true
            """;

    private static final String PILOT_STORE_CSV_CONTENT = """
            external_code;name;address;city;store_format_code;store_format_name;active
            radnicka;Europrom Radnička;Radnička 75;Valjevo;EUROPROM;Europrom;true
            kolubara-mala;Europrom Kolubara mala;Vladike Nikolaja 24;Valjevo;EUROPROM;Europrom;false
            """;

    private static final String INCOMPLETE_STORE_FORMAT_CSV_CONTENT = """
            external_code;name;address;city;store_format_code;active
            test-001;Test objekat;Test adresa 1;Valjevo;MARKET;true
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
    private ProductSearchService productSearchService;

    @Autowired
    private CanonicalProductSearchService canonicalProductSearchService;

    @Autowired
    private FuzzyProductCandidateService fuzzyCandidateService;

    @Autowired
    private ProductMatchDecisionService matchDecisionService;

    @Autowired
    private ProductMatchFeedbackService matchFeedbackService;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private RetailerLocationImportService retailerLocationImportService;

    @Autowired
    private StoreGeocodingService storeGeocodingService;

    @Autowired
    private NearbyStoreService nearbyStoreService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanBusinessData() {
        jdbcClient.sql("""
                        TRUNCATE TABLE
                            app.shopping_list_item,
                            app.shopping_list,
                            app.product_match_feedback,
                            app.product_match_decision,
                            app.price_observation,
                            app.retailer_product,
                            app.import_run,
                            app.store,
                            app.store_format,
                            app.canonical_product,
                            app.retailer
                        RESTART IDENTITY
                        """)
                .update();
    }

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

        csvServer.createContext("/exact-ean-a.csv", exchange -> {
            byte[] responseBody = EXACT_EAN_A_CSV_CONTENT
                    .getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/csv; charset=UTF-8"
            );

            exchange.sendResponseHeaders(200, responseBody.length);

            try (OutputStream outputStream =
                         exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        });

        csvServer.createContext("/exact-ean-b.csv", exchange -> {
            byte[] responseBody = EXACT_EAN_B_CSV_CONTENT
                    .getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/csv; charset=UTF-8"
            );

            exchange.sendResponseHeaders(200, responseBody.length);

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
                        SELECT run.status
                        FROM app.import_run run
                        JOIN app.retailer retailer
                          ON retailer.id = run.retailer_id
                        WHERE retailer.code = 'TEST'
                        ORDER BY run.id
                        """)
                .query(String.class)
                .list();

        String normalizedMilkName = jdbcClient.sql("""
                        SELECT product.normalized_name
                        FROM app.retailer_product product
                        JOIN app.retailer retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'TEST'
                          AND product.name = 'Mleko 1 l'
                        """)
                .query(String.class)
                .single();

        BigDecimal milkQuantity = jdbcClient.sql("""
                        SELECT product.quantity_value
                        FROM app.retailer_product product
                        JOIN app.retailer retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'TEST'
                          AND product.name = 'Mleko 1 l'
                        """)
                .query(BigDecimal.class)
                .single();

        String milkBaseUnit = jdbcClient.sql("""
                        SELECT product.base_unit
                        FROM app.retailer_product product
                        JOIN app.retailer retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'TEST'
                          AND product.name = 'Mleko 1 l'
                        """)
                .query(String.class)
                .single();

        assertThat(countAfterFirstImport).isEqualTo(2);
        assertThat(countAfterSecondImport).isEqualTo(2);

        assertThat(secondImportRunId)
                .isGreaterThan(firstImportRunId);

        assertThat(rowsPointingToFirstImport).isZero();
        assertThat(rowsPointingToSecondImport).isEqualTo(2);

        assertThat(importStatuses)
                .containsExactly("SUCCEEDED", "SUCCEEDED");

        assertThat(normalizedMilkName).isEqualTo("mleko 1 l");
        assertThat(milkQuantity).isEqualByComparingTo("1000");
        assertThat(milkBaseUnit).isEqualTo("ml");
    }

    @Test
    void productSearchReturnsSameProductForLatinAndCyrillicQuery() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES (?, ?)
                        RETURNING id
                        """)
                .param(1, "SEARCH_NORMALIZATION_TEST")
                .param(2, "Search normalization test")
                .query(Long.class)
                .single();

        Long productId = jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            name,
                            normalized_name,
                            brand,
                            quantity_value,
                            base_unit,
                            unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, "SEARCH-NORMALIZATION-PRODUCT")
                .param(3, "Čokoladno mleko Žirafa 1 l")
                .param(4, "cokoladno mleko zirafa 1 l")
                .param(5, "Test brend")
                .param(6, 1000)
                .param(7, "ml")
                .param(8, "l")
                .query(Long.class)
                .single();

        Long importRunId = jdbcClient.sql("""
                        INSERT INTO app.import_run (
                            retailer_id,
                            source_url,
                            status
                        )
                        VALUES (?, ?, 'SUCCEEDED')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, "https://example.test/search.csv")
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO app.price_observation (
                            retailer_product_id,
                            import_run_id,
                            retailer_format_name,
                            price_date,
                            regular_price
                        )
                        VALUES (?, ?, ?, DATE '2026-08-04', ?)
                        """)
                .param(1, productId)
                .param(2, importRunId)
                .param(3, "Search test format")
                .param(4, new BigDecimal("175.50"))
                .update();

        List<ProductSearchResult> latinResults =
                productSearchService.search(
                        "čokoladno mleko žirafa",
                        10
                );

        List<ProductSearchResult> cyrillicResults =
                productSearchService.search(
                        "чоколадно млеко жирафа",
                        10
                );

        assertThat(latinResults)
                .extracting(ProductSearchResult::productId)
                .containsExactly(productId);

        assertThat(cyrillicResults)
                .extracting(ProductSearchResult::productId)
                .containsExactly(productId);

        assertThat(latinResults.getFirst().name())
                .isEqualTo("Čokoladno mleko Žirafa 1 l");
    }

    @Test
    void canonicalProductSearchReturnsScoredPaginatedResults() {
        jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            barcode,
                            quantity_value,
                            base_unit
                        )
                        VALUES
                            (
                                'PK035-CATALOG-MILK-1L',
                                'Katalog Imlek mleko 1 l',
                                'katalog imlek mleko 1 l',
                                'Imlek',
                                '8609999999993',
                                1000,
                                'ml'
                            ),
                            (
                                'PK035-CATALOG-FRESH-MILK-1L',
                                'Katalog Imlek sveže mleko 1 l',
                                'katalog imlek sveze mleko 1 l',
                                'Imlek',
                                NULL,
                                1000,
                                'ml'
                            ),
                            (
                                'PK035-CATALOG-CHOCOLATE-MILK-1L',
                                'Katalog Imlek čokoladno mleko 1 l',
                                'katalog imlek cokoladno mleko 1 l',
                                'Imlek',
                                NULL,
                                1000,
                                'ml'
                            )
                        """)
                .update();

        CanonicalProductSearchPage firstPage =
                canonicalProductSearchService.search(
                        "Katalog Imlek mleko 1l",
                        0,
                        2
                );

        CanonicalProductSearchPage secondPage =
                canonicalProductSearchService.search(
                        "Каталог Имлек млеко 1л",
                        1,
                        2
                );

        assertThat(firstPage.query())
                .isEqualTo("Katalog Imlek mleko 1l");
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.limit()).isEqualTo(2);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(firstPage.items()).hasSize(2);

        assertThat(firstPage.items().getFirst().name())
                .isEqualTo("Katalog Imlek mleko 1 l");
        assertThat(firstPage.items().getFirst().brand())
                .isEqualTo("Imlek");
        assertThat(firstPage.items().getFirst().quantityValue())
                .isEqualByComparingTo("1000");
        assertThat(firstPage.items().getFirst().baseUnit())
                .isEqualTo("ml");
        assertThat(firstPage.items().getFirst().score())
                .isEqualByComparingTo("1.0000");

        assertThat(firstPage.items())
                .extracting(item -> item.score())
                .isSortedAccordingTo(
                        java.util.Comparator.reverseOrder()
                );

        assertThat(secondPage.page()).isEqualTo(1);
        assertThat(secondPage.totalElements()).isEqualTo(3);
        assertThat(secondPage.totalPages()).isEqualTo(2);
        assertThat(secondPage.hasNext()).isFalse();
        assertThat(secondPage.items()).hasSize(1);

        assertThat(firstPage.items())
                .extracting(item -> item.canonicalProductId())
                .doesNotContainAnyElementsOf(
                        secondPage.items().stream()
                                .map(item -> item.canonicalProductId())
                                .toList()
                );

        CanonicalProductSearchPage exactEanResult =
                canonicalProductSearchService.search(
                        "8609999999993",
                        0,
                        20
                );

        assertThat(exactEanResult.items()).hasSize(1);
        assertThat(exactEanResult.items().getFirst().barcode())
                .isEqualTo("8609999999993");
        assertThat(exactEanResult.items().getFirst().score())
                .isEqualByComparingTo("1.0000");

        assertThatThrownBy(() ->
                canonicalProductSearchService.search("mleko", -1, 20)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Broj stranice ne sme biti negativan");

        assertThatThrownBy(() ->
                canonicalProductSearchService.search("mleko", 0, 101)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Limit mora biti između 1 i 100");
    }

    @Test
    void sameValidEanAutomaticallyLinksRetailerProducts() {
        String serverBaseUrl = "http://127.0.0.1:"
                + csvServer.getAddress().getPort();

        jdbcClient.sql("""
                        INSERT INTO app.retailer (
                            code,
                            name,
                            dataset_url
                        )
                        VALUES (?, ?, ?), (?, ?, ?)
                        """)
                .param(1, "EXACT_EAN_A")
                .param(2, "Exact EAN prodavnica A")
                .param(3, serverBaseUrl + "/exact-ean-a.csv")
                .param(4, "EXACT_EAN_B")
                .param(5, "Exact EAN prodavnica B")
                .param(6, serverBaseUrl + "/exact-ean-b.csv")
                .update();

        priceImportService.importPrices("EXACT_EAN_A", 100);
        priceImportService.importPrices("EXACT_EAN_B", 100);

        Long canonicalProductCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.canonical_product
                        WHERE barcode = '8601234567899'
                        """)
                .query(Long.class)
                .single();

        List<Long> linkedCanonicalProductIds = jdbcClient.sql("""
                        SELECT DISTINCT product.canonical_product_id
                        FROM app.retailer_product product
                        JOIN app.retailer retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code IN (
                            'EXACT_EAN_A',
                            'EXACT_EAN_B'
                        )
                          AND product.barcode = '8601234567899'
                        """)
                .query(Long.class)
                .list();

        Long linkedRetailerProductCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.retailer_product product
                        JOIN app.retailer retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code IN (
                            'EXACT_EAN_A',
                            'EXACT_EAN_B'
                        )
                          AND product.barcode = '8601234567899'
                          AND product.canonical_product_id IS NOT NULL
                        """)
                .query(Long.class)
                .single();

        assertThat(canonicalProductCount).isEqualTo(1);
        assertThat(linkedCanonicalProductIds).hasSize(1);
        assertThat(linkedRetailerProductCount).isEqualTo(2);
    }

    @Test
    void fuzzyMatchingReturnsRankedCandidatesForEquivalentScripts() {
        jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            quantity_value,
                            base_unit
                        )
                        VALUES
                            (
                                'FUZZY-IMLEK-1L',
                                'Imlek mleko 1 l',
                                'imlek mleko 1 l',
                                'Imlek',
                                1000,
                                'ml'
                            ),
                            (
                                'FUZZY-IMLEK-FRESH-1L',
                                'Imlek sveže mleko 1 l',
                                'imlek sveze mleko 1 l',
                                'Imlek',
                                1000,
                                'ml'
                            ),
                            (
                                'FUZZY-IMLEK-15L',
                                'Imlek mleko 1,5 l',
                                'imlek mleko 1 5 l',
                                'Imlek',
                                1500,
                                'ml'
                            ),
                            (
                                'FUZZY-KRAVICA-1L',
                                'Moja Kravica mleko 1 l',
                                'moja kravica mleko 1 l',
                                'Moja Kravica',
                                1000,
                                'ml'
                            ),
                            (
                                'FUZZY-IMLEK-CHOCOLATE-1L',
                                'Imlek čokoladno mleko 1 l',
                                'imlek cokoladno mleko 1 l',
                                'Imlek',
                                1000,
                                'ml'
                            ),
                            (
                                'FUZZY-BREAD-500G',
                                'Beli hleb 500 g',
                                'beli hleb 500 g',
                                'Test pekara',
                                500,
                                'g'
                            )
                        """)
                .update();

        List<FuzzyProductCandidate> latinCandidates =
                fuzzyCandidateService.findCandidates(
                        "Imlek mleko 1l",
                        3
                );

        List<FuzzyProductCandidate> cyrillicCandidates =
                fuzzyCandidateService.findCandidates(
                        "Имлек млеко 1л",
                        3
                );

        assertThat(latinCandidates).hasSize(3);
        assertThat(latinCandidates.getFirst().name())
                .isEqualTo("Imlek mleko 1 l");
        assertThat(latinCandidates.getFirst().nameSimilarity())
                .isEqualByComparingTo("1.0000");

        assertThat(latinCandidates.getFirst().score().totalScore())
                .isEqualByComparingTo("1.0000");

        assertThat(latinCandidates.getFirst().score())
                .satisfies(score -> {
                    assertThat(score.nameContribution())
                            .isEqualByComparingTo("0.4118");
                    assertThat(score.brandContribution())
                            .isEqualByComparingTo("0.2941");
                    assertThat(score.packageContribution())
                            .isEqualByComparingTo("0.2941");
                    assertThat(score.reasons()).hasSize(3);
                });

        assertThat(latinCandidates)
                .extracting(candidate ->
                        candidate.score().totalScore()
                )
                .isSortedAccordingTo(
                        java.util.Comparator.reverseOrder()
                );

        assertThat(cyrillicCandidates)
                .extracting(FuzzyProductCandidate::canonicalProductId)
                .containsExactlyElementsOf(
                        latinCandidates.stream()
                                .map(FuzzyProductCandidate::canonicalProductId)
                                .toList()
                );

        assertThat(latinCandidates)
                .extracting(FuzzyProductCandidate::name)
                .doesNotContain("Beli hleb 500 g");
    }

    @Test
    void fuzzyMatchingRejectsInvalidQueryAndCandidateLimit() {
        assertThatThrownBy(() ->
                fuzzyCandidateService.findCandidates("   ", 3)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Parametar query ne sme biti prazan");

        assertThatThrownBy(() ->
                fuzzyCandidateService.findCandidates("mleko", 2)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Limit za matching kandidate mora biti između 3 i 5"
                );

        assertThatThrownBy(() ->
                fuzzyCandidateService.findCandidates("mleko", 6)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Limit za matching kandidate mora biti između 3 i 5"
                );
    }

    @Test
    void explainableScoreCanRerankAWeakerNameMatch() {
        jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            quantity_value,
                            base_unit
                        )
                        VALUES
                            (
                                'SCORE-ALFA-WRONG-15L',
                                'Alfa jogurt 1 l',
                                'alfa jogurt 1 l',
                                'Beta',
                                1500,
                                'ml'
                            ),
                            (
                                'SCORE-ALFA-RIGHT-1L',
                                'Alfa probiotski jogurt 1 l',
                                'alfa probiotski jogurt 1 l',
                                'Alfa',
                                1000,
                                'ml'
                            )
                        """)
                .update();

        List<FuzzyProductCandidate> candidates =
                fuzzyCandidateService.findCandidates(
                        "Alfa jogurt 1l",
                        3
                );

        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst().name())
                .isEqualTo("Alfa probiotski jogurt 1 l");
        assertThat(candidates.getFirst().nameSimilarity())
                .isLessThan(candidates.get(1).nameSimilarity());
        assertThat(candidates.getFirst().score().totalScore())
                .isGreaterThan(
                        candidates.get(1).score().totalScore()
                );
    }

    @Test
    void matchThresholdsPersistDecisionWithoutSilentlySelectingLowScore() {
        Long automaticCandidateId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            quantity_value,
                            base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "PK034-AUTO-CANDIDATE")
                .param(2, "Autoaccept Imlek mleko 1 l")
                .param(3, "autoaccept imlek mleko 1 l")
                .param(4, "Imlek")
                .param(5, 1000)
                .param(6, "ml")
                .query(Long.class)
                .single();

        Long lowScoreCandidateId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            quantity_value,
                            base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "PK034-LOW-CANDIDATE")
                .param(2, "Lowscore neutralni artikal 1 l")
                .param(3, "lowscore neutralni artikal 1 l")
                .param(4, "Drugi brend")
                .param(5, 1500)
                .param(6, "ml")
                .query(Long.class)
                .single();

        ProductMatchDecision automaticDecision =
                matchDecisionService.decide(
                        "Autoaccept Imlek mleko 1l",
                        3
                );

        ProductMatchDecision lowScoreDecision =
                matchDecisionService.decide(
                        "Lowscore neutralni artikal 1l",
                        3
                );

        assertThat(automaticDecision.status())
                .isEqualTo(ProductMatchStatus.AUTO_ACCEPTED);
        assertThat(automaticDecision.matchedCanonicalProductId())
                .isEqualTo(automaticCandidateId);
        assertThat(automaticDecision.score())
                .isEqualByComparingTo("1.0000");

        assertThat(lowScoreDecision.status())
                .isEqualTo(ProductMatchStatus.UNMATCHED);
        assertThat(lowScoreDecision.matchedCanonicalProductId())
                .isNull();
        assertThat(lowScoreDecision.candidates().getFirst()
                .canonicalProductId())
                .isEqualTo(lowScoreCandidateId);
        assertThat(lowScoreDecision.score())
                .isEqualByComparingTo("0.4118");

        Long automaticDecisionRows = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.product_match_decision
                        WHERE id = ?
                          AND status = 'AUTO_ACCEPTED'
                          AND top_candidate_id = ?
                          AND matched_canonical_product_id = ?
                          AND score = 1.0000
                          AND algorithm_version =
                              'fuzzy-name-brand-package-v1'
                        """)
                .param(1, automaticDecision.decisionId())
                .param(2, automaticCandidateId)
                .param(3, automaticCandidateId)
                .query(Long.class)
                .single();

        Long lowScoreDecisionRows = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.product_match_decision
                        WHERE id = ?
                          AND status = 'UNMATCHED'
                          AND top_candidate_id = ?
                          AND matched_canonical_product_id IS NULL
                          AND score = 0.4118
                          AND algorithm_version =
                              'fuzzy-name-brand-package-v1'
                        """)
                .param(1, lowScoreDecision.decisionId())
                .param(2, lowScoreCandidateId)
                .query(Long.class)
                .single();

        assertThat(automaticDecisionRows).isEqualTo(1);
        assertThat(lowScoreDecisionRows).isEqualTo(1);
    }

    @Test
    void userFeedbackIsAppendOnlyAndLatestConfirmationIsReused() {
        Long algorithmCandidateId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            quantity_value,
                            base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "PK036-ALGORITHM-CANDIDATE")
                .param(2, "Audit Imlek mleko 1 l")
                .param(3, "audit imlek mleko 1 l")
                .param(4, "Imlek")
                .param(5, 1000)
                .param(6, "ml")
                .query(Long.class)
                .single();

        Long userSelectedProductId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            brand,
                            quantity_value,
                            base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "PK036-USER-SELECTION")
                .param(2, "Audit Imlek sveže mleko 1 l")
                .param(3, "audit imlek sveze mleko 1 l")
                .param(4, "Imlek")
                .param(5, 1000)
                .param(6, "ml")
                .query(Long.class)
                .single();

        String clientToken = "test-device-pk036";

        ProductMatchDecision algorithmDecision =
                matchDecisionService.decide(
                        "Audit Imlek mleko 1l",
                        3,
                        clientToken
                );

        assertThat(algorithmDecision.source())
                .isEqualTo(ProductMatchDecisionSource.ALGORITHM);
        assertThat(algorithmDecision.matchedCanonicalProductId())
                .isEqualTo(algorithmCandidateId);

        ProductMatchFeedback rejected = matchFeedbackService.record(
                algorithmDecision.decisionId(),
                new ProductMatchFeedbackRequest(
                        clientToken,
                        ProductMatchFeedbackAction.REJECTED,
                        null,
                        "Automatski izbor nije proizvod koji korisnik želi"
                )
        );

        ProductMatchDecision reusedRejection =
                matchDecisionService.decide(
                        "Audit Imlek mleko 1l",
                        3,
                        clientToken
                );

        assertThat(rejected.reusable()).isTrue();
        assertThat(reusedRejection.source())
                .isEqualTo(ProductMatchDecisionSource.USER_REJECTION);
        assertThat(reusedRejection.status())
                .isEqualTo(ProductMatchStatus.UNMATCHED);
        assertThat(reusedRejection.matchedCanonicalProductId())
                .isNull();
        assertThat(reusedRejection.reusedFeedbackId())
                .isEqualTo(rejected.feedbackId());

        ProductMatchFeedback confirmed = matchFeedbackService.record(
                algorithmDecision.decisionId(),
                new ProductMatchFeedbackRequest(
                        clientToken,
                        ProductMatchFeedbackAction.CONFIRMED,
                        userSelectedProductId,
                        "Korisnik je izabrao sveže mleko"
                )
        );

        ProductMatchDecision reusedDecision =
                matchDecisionService.decide(
                        "Аудит Имлек млеко 1л",
                        3,
                        clientToken
                );

        assertThat(confirmed.reusable()).isTrue();
        assertThat(reusedDecision.source())
                .isEqualTo(ProductMatchDecisionSource.USER_CONFIRMATION);
        assertThat(reusedDecision.matchedCanonicalProductId())
                .isEqualTo(userSelectedProductId);
        assertThat(reusedDecision.reusedFeedbackId())
                .isEqualTo(confirmed.feedbackId());
        assertThat(reusedDecision.candidates()).isEmpty();

        List<String> feedbackActions = jdbcClient.sql("""
                        SELECT action
                        FROM app.product_match_feedback
                        WHERE decision_id = ?
                        ORDER BY id
                        """)
                .param(1, algorithmDecision.decisionId())
                .query(String.class)
                .list();

        String originalDecisionStatus = jdbcClient.sql("""
                        SELECT status
                        FROM app.product_match_decision
                        WHERE id = ?
                        """)
                .param(1, algorithmDecision.decisionId())
                .query(String.class)
                .single();

        Long decisionsForClient = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.product_match_decision
                        WHERE client_token = ?
                        """)
                .param(1, clientToken)
                .query(Long.class)
                .single();

        assertThat(feedbackActions)
                .containsExactly("REJECTED", "CONFIRMED");
        assertThat(originalDecisionStatus)
                .isEqualTo(algorithmDecision.status().name());
        assertThat(decisionsForClient).isEqualTo(1);

        ProductMatchDecision otherClientDecision =
                matchDecisionService.decide(
                        "Audit Imlek mleko 1l",
                        3,
                        "other-test-device"
                );

        assertThat(otherClientDecision.source())
                .isEqualTo(ProductMatchDecisionSource.ALGORITHM);
        assertThat(otherClientDecision.matchedCanonicalProductId())
                .isEqualTo(algorithmCandidateId);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE app.product_match_feedback
                        SET note = 'Pokušaj izmene istorije'
                        WHERE id = ?
                        """)
                .param(1, confirmed.feedbackId())
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void userFeedbackRejectsInvalidActionProductAndClientCombinations() {
        Long canonicalProductId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name
                        )
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "PK036-VALIDATION-PRODUCT")
                .param(2, "Validation proizvod")
                .param(3, "validation proizvod")
                .query(Long.class)
                .single();

        ProductMatchDecision decision = matchDecisionService.decide(
                "Validation proizvod",
                3,
                "validation-client"
        );

        assertThatThrownBy(() -> matchFeedbackService.record(
                decision.decisionId(),
                new ProductMatchFeedbackRequest(
                        "different-client",
                        ProductMatchFeedbackAction.CONFIRMED,
                        canonicalProductId,
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Odluka o uparivanju ne postoji za dati clientToken"
                );

        assertThatThrownBy(() -> matchFeedbackService.record(
                decision.decisionId(),
                new ProductMatchFeedbackRequest(
                        "validation-client",
                        ProductMatchFeedbackAction.CONFIRMED,
                        null,
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "selectedCanonicalProductId je obavezan za potvrdu"
                );

        assertThatThrownBy(() -> matchFeedbackService.record(
                decision.decisionId(),
                new ProductMatchFeedbackRequest(
                        "validation-client",
                        ProductMatchFeedbackAction.REJECTED,
                        canonicalProductId,
                        null
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Odbijanje ne sme da izabere kanonski proizvod"
                );
    }

    @Test
    void canonicalProductAcceptsValidDataAndCanBeLinkedToRetailerProduct() {
        Long canonicalProductId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            brand,
                            barcode,
                            quantity_value,
                            base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "TEST-MLEKO-1L")
                .param(2, "Test mleko 1 l")
                .param(3, "Test brend")
                .param(4, "8600000000100")
                .param(5, 1000)
                .param(6, "ml")
                .query(Long.class)
                .single();

        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES (?, ?)
                        RETURNING id
                        """)
                .param(1, "CANONICAL_TEST")
                .param(2, "Canonical test prodavnica")
                .query(Long.class)
                .single();

        Long retailerProductId = jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            name,
                            canonical_product_id
                        )
                        VALUES (?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, "TEST-MLEKO-SOURCE-1")
                .param(3, "Test mleko")
                .param(4, canonicalProductId)
                .query(Long.class)
                .single();

        Long linkedCanonicalProductId = jdbcClient.sql("""
                        SELECT canonical_product_id
                        FROM app.retailer_product
                        WHERE id = ?
                        """)
                .param(1, retailerProductId)
                .query(Long.class)
                .single();

        assertThat(linkedCanonicalProductId)
                .isEqualTo(canonicalProductId);
    }

    @Test
    void canonicalProductRejectsBlankRequiredValues() {
        assertCanonicalProductInsertFails(
                "   ",
                "Validan naziv",
                null,
                null
        );

        assertCanonicalProductInsertFails(
                "BLANK-NAME",
                "   ",
                null,
                null
        );
    }

    @Test
    void canonicalProductRejectsNonPositiveQuantity() {
        assertCanonicalProductInsertFails(
                "ZERO-QUANTITY",
                "Nulta količina",
                null,
                0
        );

        assertCanonicalProductInsertFails(
                "NEGATIVE-QUANTITY",
                "Negativna količina",
                null,
                -1
        );
    }

    @Test
    void canonicalProductRejectsInvalidBarcode() {
        assertCanonicalProductInsertFails(
                "SHORT-BARCODE",
                "Kratak barkod",
                "1234567",
                null
        );

        assertCanonicalProductInsertFails(
                "NON-NUMERIC-BARCODE",
                "Barkod sa slovom",
                "8600000A00001",
                null
        );

        assertCanonicalProductInsertFails(
                "ZERO-BARCODE",
                "Nulti barkod",
                "00000000",
                null
        );
    }

    @Test
    void canonicalProductRejectsDuplicateCanonicalKeyAndBarcode() {
        insertCanonicalProduct(
                "UNIQUE-PRODUCT",
                "Jedinstveni proizvod",
                "8600000000200",
                1
        );

        assertCanonicalProductInsertFails(
                "UNIQUE-PRODUCT",
                "Drugi naziv",
                "8600000000201",
                1
        );

        assertCanonicalProductInsertFails(
                "OTHER-PRODUCT",
                "Drugi proizvod",
                "8600000000200",
                1
        );
    }

    @Test
    void retailerProductRejectsUnknownCanonicalProduct() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES (?, ?)
                        RETURNING id
                        """)
                .param(1, "FK_TEST")
                .param(2, "Foreign key test prodavnica")
                .query(Long.class)
                .single();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            name,
                            canonical_product_id
                        )
                        VALUES (?, ?, ?, ?)
                        """)
                .param(1, retailerId)
                .param(2, "UNKNOWN-CANONICAL-SOURCE")
                .param(3, "Nepovezani proizvod")
                .param(4, Long.MAX_VALUE)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storeModelRepresentsChainFormatAndPhysicalObjects() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK037_CHAIN', 'PK-037 test lanac')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long storeFormatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'SUPERMARKET', 'Supermarket')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO app.store (
                            retailer_id,
                            store_format_id,
                            external_code,
                            name,
                            address,
                            city,
                            location,
                            active
                        )
                        VALUES (
                            ?, ?, 'BG-001', 'Centar',
                            'Knez Mihailova 1', 'Beograd',
                            ST_SetSRID(
                                ST_MakePoint(20.4569, 44.8176),
                                4326
                            )::geography,
                            TRUE
                        ), (
                            ?, ?, 'BG-002', 'Novi Beograd',
                            'Bulevar Mihajla Pupina 1', 'Beograd',
                            NULL,
                            TRUE
                        )
                        """)
                .param(1, retailerId)
                .param(2, storeFormatId)
                .param(3, retailerId)
                .param(4, storeFormatId)
                .update();

        List<StoreFormat> formats =
                storeRepository.findFormatsByRetailerCode(
                        "PK037_CHAIN"
                );

        List<Store> stores =
                storeRepository.findStoresByRetailerCode(
                        "PK037_CHAIN"
                );

        String locationIndexDefinition = jdbcClient.sql("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'app'
                          AND tablename = 'store'
                          AND indexname = 'idx_store_location'
                        """)
                .query(String.class)
                .single();

        assertThat(formats).hasSize(1);
        assertThat(formats.getFirst().retailerCode())
                .isEqualTo("PK037_CHAIN");
        assertThat(formats.getFirst().code())
                .isEqualTo("SUPERMARKET");

        assertThat(stores).hasSize(2);
        assertThat(stores.getFirst().retailerName())
                .isEqualTo("PK-037 test lanac");
        assertThat(stores.getFirst().storeFormatCode())
                .isEqualTo("SUPERMARKET");
        assertThat(stores.getFirst().externalCode())
                .isEqualTo("BG-001");
        assertThat(stores.getFirst().latitude())
                .isEqualTo(44.8176);
        assertThat(stores.getFirst().longitude())
                .isEqualTo(20.4569);

        assertThat(stores.get(1).externalCode())
                .isEqualTo("BG-002");
        assertThat(stores.get(1).latitude()).isNull();
        assertThat(stores.get(1).longitude()).isNull();

        assertThat(locationIndexDefinition.toLowerCase())
                .contains("using gist (location)");
    }

    @Test
    void storeRejectsFormatOwnedByAnotherRetailer() {
        List<Long> retailerIds = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES
                            ('PK037_OWNER', 'Vlasnik formata'),
                            ('PK037_OTHER', 'Drugi lanac')
                        RETURNING id
                        """)
                .query(Long.class)
                .list();

        Long foreignStoreFormatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'MINI', 'Mini market')
                        RETURNING id
                        """)
                .param(retailerIds.getFirst())
                .query(Long.class)
                .single();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO app.store (
                            retailer_id,
                            store_format_id,
                            external_code,
                            name
                        )
                        VALUES (?, ?, 'INVALID-001', 'Pogrešan lanac')
                        """)
                .param(1, retailerIds.get(1))
                .param(2, foreignStoreFormatId)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existingLocationImportWritesToStoreWithDefaultFormat() {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK037_IMPORT', 'Import test lanac')
                        """)
                .update();

        RetailerLocationImportResult result =
                retailerLocationImportService.importLocations(
                        "PK037_IMPORT",
                        new ByteArrayInputStream(
                                RETAILER_LOCATION_CSV_CONTENT.getBytes(
                                        StandardCharsets.UTF_8
                                )
                        ),
                        100
                );

        List<Store> stores =
                storeRepository.findStoresByRetailerCode(
                        "PK037_IMPORT"
                );

        assertThat(result.rowsRead()).isEqualTo(1);
        assertThat(result.rowsSaved()).isEqualTo(1);
        assertThat(result.rowsSkipped()).isZero();
        assertThat(result.status()).isEqualTo("SUCCEEDED");

        assertThat(stores).hasSize(1);
        assertThat(stores.getFirst().externalCode())
                .isEqualTo("PK037-IMPORT-001");
        assertThat(stores.getFirst().storeFormatCode())
                .isEqualTo("STANDARD");
    }

    @Test
    void pilotStoreImportPersistsAddressFormatAndActiveStatus() {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK038_EUROPROM', 'Europrom pilot')
                        """)
                .update();

        RetailerLocationImportResult result =
                retailerLocationImportService.importLocations(
                        "PK038_EUROPROM",
                        new ByteArrayInputStream(
                                PILOT_STORE_CSV_CONTENT.getBytes(
                                        StandardCharsets.UTF_8
                                )
                        ),
                        100
                );

        List<StoreFormat> formats =
                storeRepository.findFormatsByRetailerCode(
                        "PK038_EUROPROM"
                );

        List<Store> stores =
                storeRepository.findStoresByRetailerCode(
                        "PK038_EUROPROM"
                );

        assertThat(result.rowsRead()).isEqualTo(2);
        assertThat(result.rowsSaved()).isEqualTo(2);
        assertThat(result.rowsSkipped()).isZero();
        assertThat(result.status()).isEqualTo("SUCCEEDED");

        assertThat(formats).hasSize(1);
        assertThat(formats.getFirst().code())
                .isEqualTo("EUROPROM");
        assertThat(formats.getFirst().name())
                .isEqualTo("Europrom");

        assertThat(stores).hasSize(2);
        assertThat(stores.getFirst().address())
                .isEqualTo("Vladike Nikolaja 24");
        assertThat(stores.getFirst().city())
                .isEqualTo("Valjevo");
        assertThat(stores.getFirst().storeFormatCode())
                .isEqualTo("EUROPROM");
        assertThat(stores.getFirst().latitude()).isNull();
        assertThat(stores.getFirst().longitude()).isNull();
        assertThat(stores.getFirst().active()).isFalse();

        assertThat(stores.get(1).address())
                .isEqualTo("Radnička 75");
        assertThat(stores.get(1).city())
                .isEqualTo("Valjevo");
        assertThat(stores.get(1).storeFormatName())
                .isEqualTo("Europrom");
        assertThat(stores.get(1).latitude()).isNull();
        assertThat(stores.get(1).longitude()).isNull();
        assertThat(stores.get(1).active()).isTrue();
    }

    @Test
    void storeImportRequiresFormatCodeAndNameTogether() {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK038_FORMAT', 'Format validation')
                        """)
                .update();

        assertThatThrownBy(() ->
                retailerLocationImportService.importLocations(
                        "PK038_FORMAT",
                        new ByteArrayInputStream(
                                INCOMPLETE_STORE_FORMAT_CSV_CONTENT.getBytes(
                                        StandardCharsets.UTF_8
                                )
                        ),
                        100
                ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("store_format_code")
                .hasMessageContaining("store_format_name");
    }

    @Test
    void reliableStoreGeocodingIsAppliedAndReusedFromCache() {
        Long storeId = insertStoreWaitingForGeocoding(
                "PK039_AUTO",
                "AUTO-001",
                "Radnička 75",
                "Valjevo"
        );

        StoreGeocodingCandidateRequest request =
                new StoreGeocodingCandidateRequest(
                        44.2701,
                        19.8842,
                        new BigDecimal("0.9500"),
                        "test geocoder",
                        "https://example.test/geocoding/auto-001",
                        "Радничка 75, Ваљево, Србија"
                );

        StoreGeocodingResult first =
                storeGeocodingService.recordCandidate(
                        storeId,
                        request
                );

        StoreGeocodingResult cached =
                storeGeocodingService.recordCandidate(
                        storeId,
                        request
                );

        assertThat(first.status())
                .isEqualTo(StoreGeocodingStatus.AUTO_VERIFIED);
        assertThat(first.coordinatesApplied()).isTrue();
        assertThat(first.cached()).isFalse();
        assertThat(first.source()).isEqualTo("TEST_GEOCODER");
        assertThat(first.confidence())
                .isEqualByComparingTo("0.9500");

        assertThat(cached.cached()).isTrue();
        assertThat(cached.geocodedAt())
                .isEqualTo(first.geocodedAt());

        List<Double> coordinates = jdbcClient.sql("""
                        SELECT ST_Y(location::geometry),
                               ST_X(location::geometry)
                        FROM app.store
                        WHERE id = ?
                        """)
                .param(storeId)
                .query((resultSet, rowNumber) -> List.of(
                        resultSet.getDouble(1),
                        resultSet.getDouble(2)
                ))
                .single();

        assertThat(coordinates)
                .containsExactly(44.2701, 19.8842);
    }

    @Test
    void suspiciousStoreGeocodingWaitsForManualReview() {
        Long storeId = insertStoreWaitingForGeocoding(
                "PK039_REVIEW",
                "REVIEW-001",
                "Vladike Nikolaja 24",
                "Valjevo"
        );

        StoreGeocodingCandidateRequest suspiciousRequest =
                new StoreGeocodingCandidateRequest(
                        44.2710,
                        19.8850,
                        new BigDecimal("0.6200"),
                        "test geocoder",
                        "https://example.test/geocoding/review-001",
                        "Vladike Nikolaja, Beograd"
                );

        StoreGeocodingResult suspicious =
                storeGeocodingService.recordCandidate(
                        storeId,
                        suspiciousRequest
                );

        List<StoreGeocodingResult> reviewQueue =
                storeGeocodingService.findReviewQueue("Valjevo");

        assertThat(suspicious.status())
                .isEqualTo(StoreGeocodingStatus.NEEDS_REVIEW);
        assertThat(suspicious.coordinatesApplied()).isFalse();
        assertThat(suspicious.suspiciousReason())
                .contains("LOW_CONFIDENCE")
                .contains("CITY_MISMATCH")
                .contains("HOUSE_NUMBER_MISMATCH");
        assertThat(reviewQueue)
                .extracting(StoreGeocodingResult::storeId)
                .containsExactly(storeId);

        Double locationBeforeReview = jdbcClient.sql("""
                        SELECT ST_X(location::geometry)
                        FROM app.store
                        WHERE id = ?
                        """)
                .param(storeId)
                .query(Double.class)
                .optional()
                .orElse(null);

        assertThat(locationBeforeReview).isNull();

        StoreGeocodingResult reviewed =
                storeGeocodingService.review(
                        storeId,
                        new StoreGeocodingReviewRequest(
                                true,
                                44.2722,
                                19.8863,
                                "Adresa i ulaz su ručno provereni."
                        )
                );

        assertThat(reviewed.status())
                .isEqualTo(
                        StoreGeocodingStatus.MANUALLY_VERIFIED
                );
        assertThat(reviewed.coordinatesApplied()).isTrue();
        assertThat(reviewed.candidateLatitude())
                .isEqualTo(44.2710);
        assertThat(reviewed.candidateLongitude())
                .isEqualTo(19.8850);
        assertThat(reviewed.appliedLatitude())
                .isEqualTo(44.2722);
        assertThat(reviewed.appliedLongitude())
                .isEqualTo(19.8863);
        assertThat(reviewed.reviewNote())
                .isEqualTo("Adresa i ulaz su ručno provereni.");
        assertThat(reviewed.reviewedAt()).isNotNull();
        assertThat(
                storeGeocodingService.findReviewQueue("Valjevo")
        ).isEmpty();

        StoreGeocodingResult cachedAfterReview =
                storeGeocodingService.recordCandidate(
                        storeId,
                        suspiciousRequest
                );

        assertThat(cachedAfterReview.cached()).isTrue();
        assertThat(cachedAfterReview.status())
                .isEqualTo(
                        StoreGeocodingStatus.MANUALLY_VERIFIED
                );
        assertThat(cachedAfterReview.appliedLatitude())
                .isEqualTo(44.2722);
        assertThat(cachedAfterReview.appliedLongitude())
                .isEqualTo(19.8863);
    }

    @Test
    void nearbyStoresAreVerifiedActiveInsideRadiusAndSortedByDistance() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK040_CHAIN', 'PK-040 test lanac')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long activeFormatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name,
                            active
                        )
                        VALUES (?, 'MARKET', 'Market', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        Long inactiveFormatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name,
                            active
                        )
                        VALUES (?, 'CLOSED_FORMAT', 'Zatvoren format', FALSE)
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        Long nearestId = insertVerifiedStore(
                retailerId,
                activeFormatId,
                "NEAREST",
                "Najbliži objekat",
                44.2701,
                19.8842,
                true
        );

        Long secondId = insertVerifiedStore(
                retailerId,
                activeFormatId,
                "SECOND",
                "Drugi objekat",
                44.2750,
                19.8900,
                true
        );

        insertVerifiedStore(
                retailerId,
                activeFormatId,
                "OUTSIDE",
                "Objekat van radijusa",
                44.3200,
                19.9500,
                true
        );

        insertVerifiedStore(
                retailerId,
                activeFormatId,
                "INACTIVE",
                "Neaktivan objekat",
                44.2702,
                19.8843,
                false
        );

        insertVerifiedStore(
                retailerId,
                inactiveFormatId,
                "INACTIVE_FORMAT",
                "Objekat neaktivnog formata",
                44.2703,
                19.8844,
                true
        );

        insertStoreWaitingForGeocoding(
                "PK040_REVIEW",
                "WAITING_REVIEW",
                "Radnička 76",
                "Valjevo"
        );

        List<NearbyStore> results = nearbyStoreService.findNearby(
                44.2700,
                19.8840,
                2_000,
                10
        );

        assertThat(results)
                .extracting(NearbyStore::storeId)
                .containsExactly(nearestId, secondId);

        assertThat(results.getFirst().retailerCode())
                .isEqualTo("PK040_CHAIN");
        assertThat(results.getFirst().storeFormatCode())
                .isEqualTo("MARKET");
        assertThat(results.getFirst().externalCode())
                .isEqualTo("NEAREST");
        assertThat(results.getFirst().latitude())
                .isEqualTo(44.2701);
        assertThat(results.getFirst().longitude())
                .isEqualTo(19.8842);
        assertThat(results.getFirst().distanceMeters())
                .isLessThan(results.get(1).distanceMeters());

        assertThat(nearbyStoreService.findNearby(
                44.2700,
                19.8840,
                2_000,
                1
        )).extracting(NearbyStore::storeId)
                .containsExactly(nearestId);
    }

    @Test
    void nearbyStoresRejectInvalidCoordinatesRadiusAndLimit() {
        assertThatThrownBy(() -> nearbyStoreService.findNearby(
                Double.NaN,
                19.8840,
                5_000,
                20
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("latitude");

        assertThatThrownBy(() -> nearbyStoreService.findNearby(
                44.2700,
                181,
                5_000,
                20
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("longitude");

        assertThatThrownBy(() -> nearbyStoreService.findNearby(
                44.2700,
                19.8840,
                0,
                20
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("radiusMeters");

        assertThatThrownBy(() -> nearbyStoreService.findNearby(
                44.2700,
                19.8840,
                5_000,
                101
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void shoppingItemPreservesRawInputQuantityRuleAndPendingState() {
        String clientToken = "pk043-pending-state";

        ShoppingListSummary shoppingList =
                shoppingListService.create(
                        new CreateShoppingListRequest(
                                "Nedeljna kupovina"
                        ),
                        clientToken
                );

        ShoppingListItemResponse createdItem =
                shoppingListService.addItem(
                        shoppingList.id(),
                        clientToken,
                        new AddShoppingListItemRequest(
                                "Mleko 1 l",
                                "  2 x Mleko 1 l  ",
                                null,
                                new BigDecimal("2"),
                                ShoppingItemRule.FLEXIBLE_CATEGORY
                        )
                );

        assertThat(createdItem.name())
                .isEqualTo("Mleko 1 l");
        assertThat(createdItem.rawInput())
                .isEqualTo("  2 x Mleko 1 l  ");
        assertThat(createdItem.quantity())
                .isEqualByComparingTo("2");
        assertThat(createdItem.matchingRule())
                .isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(createdItem.matchingStatus())
                .isEqualTo(ShoppingItemMatchingStatus.PENDING);
        assertThat(createdItem.matchedCanonicalProductId())
                .isNull();

        ShoppingListResponse reloaded =
                shoppingListService.findById(
                        shoppingList.id(),
                        clientToken
                );

        assertThat(reloaded.items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.rawInput())
                            .isEqualTo("  2 x Mleko 1 l  ");
                    assertThat(item.matchingRule())
                            .isEqualTo(
                                    ShoppingItemRule.FLEXIBLE_CATEGORY
                            );
                    assertThat(item.matchingStatus())
                            .isEqualTo(
                                    ShoppingItemMatchingStatus.PENDING
                            );
                });
    }

    @Test
    void selectedBarcodeLinksCanonicalProductAndStateCannotDrift() {
        String clientToken = "pk043-confirmed-state";

        insertCanonicalProduct(
                "PK043:8601234567899",
                "Sok od narandže 1 l",
                "8601234567899",
                1
        );

        Long canonicalProductId = jdbcClient.sql("""
                        SELECT id
                        FROM app.canonical_product
                        WHERE barcode = '8601234567899'
                        """)
                .query(Long.class)
                .single();

        ShoppingListSummary shoppingList =
                shoppingListService.create(
                        new CreateShoppingListRequest("Piće"),
                        clientToken
                );

        ShoppingListItemResponse item =
                shoppingListService.addItem(
                        shoppingList.id(),
                        clientToken,
                        new AddShoppingListItemRequest(
                                "Sok od narandže 1 l",
                                null,
                                "8601234567899",
                                BigDecimal.ONE,
                                ShoppingItemRule.EXACT_PRODUCT
                        )
                );

        assertThat(item.rawInput())
                .isEqualTo("Sok od narandže 1 l");
        assertThat(item.matchingStatus())
                .isEqualTo(ShoppingItemMatchingStatus.CONFIRMED);
        assertThat(item.matchedCanonicalProductId())
                .isEqualTo(canonicalProductId);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE app.shopping_list_item
                        SET matched_canonical_product_id = NULL
                        WHERE id = ?
                        """)
                .param(item.id())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shoppingListCrudIsScopedToTemporaryClientToken() {
        String firstClientToken = "pk044-first-client";
        String secondClientToken = "pk044-second-client";

        ShoppingListSummary firstList = shoppingListService.create(
                new CreateShoppingListRequest("Prva lista"),
                firstClientToken
        );

        ShoppingListSummary secondList = shoppingListService.create(
                new CreateShoppingListRequest("Druga lista"),
                secondClientToken
        );

        assertThat(shoppingListService.findAll(firstClientToken))
                .extracting(ShoppingListSummary::id)
                .containsExactly(firstList.id());

        ShoppingListResponse renamed = shoppingListService.updateList(
                firstList.id(),
                firstClientToken,
                new UpdateShoppingListRequest("Preimenovana lista")
        );

        assertThat(renamed.name()).isEqualTo("Preimenovana lista");

        shoppingListService.addItem(
                firstList.id(),
                firstClientToken,
                new AddShoppingListItemRequest(
                        "Hleb",
                        null,
                        null,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT
                )
        );

        assertThat(shoppingListService.findById(
                firstList.id(),
                firstClientToken
        ).items()).hasSize(1);

        String storedTokenHash = jdbcClient.sql("""
                        SELECT client_token_hash
                        FROM app.shopping_list
                        WHERE id = ?
                        """)
                .param(1, firstList.id())
                .query(String.class)
                .single();

        assertThat(storedTokenHash)
                .hasSize(64)
                .isNotEqualTo(firstClientToken);

        shoppingListService.deleteList(
                firstList.id(),
                firstClientToken
        );

        assertThat(shoppingListService.findAll(firstClientToken))
                .isEmpty();

        assertThatThrownBy(() -> shoppingListService.findById(
                firstList.id(),
                firstClientToken
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Spisak nije pronađen");

        assertThat(jdbcClient.sql("""
                        SELECT active
                        FROM app.shopping_list
                        WHERE id = ?
                        """)
                .param(1, firstList.id())
                .query(Boolean.class)
                .single()).isFalse();

        assertThat(shoppingListService.findAll(secondClientToken))
                .extracting(ShoppingListSummary::id)
                .containsExactly(secondList.id());
    }

    @Test
    void shoppingListRejectsMissingOrForeignClientToken() {
        String ownerToken = "pk044-owner";
        String foreignToken = "pk044-foreign";

        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Privatna lista"),
                ownerToken
        );

        assertThatThrownBy(() -> shoppingListService.create(
                new CreateShoppingListRequest("Bez tokena"),
                " "
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("X-Client-Token");

        assertThatThrownBy(() -> shoppingListService.findById(
                shoppingList.id(),
                foreignToken
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Spisak nije pronađen");

        assertThatThrownBy(() -> shoppingListService.updateList(
                shoppingList.id(),
                foreignToken,
                new UpdateShoppingListRequest("Tuđa izmena")
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Spisak nije pronađen");

        assertThatThrownBy(() -> shoppingListService.addItem(
                shoppingList.id(),
                foreignToken,
                new AddShoppingListItemRequest(
                        "Tuđa stavka",
                        null,
                        null,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Spisak nije pronađen");

        assertThatThrownBy(() -> shoppingListService.deleteList(
                shoppingList.id(),
                foreignToken
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Spisak nije pronađen");

        assertThat(shoppingListService.findById(
                shoppingList.id(),
                ownerToken
        ).name()).isEqualTo("Privatna lista");
    }

    @Test
    void pastedShoppingListCreatesEveryNonBlankLineAndPreservesRawInput() {
        String clientToken = "pk045-paste-owner";

        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Zalepljeni spisak"),
                clientToken
        );

        PasteShoppingListItemsResponse result =
                shoppingListService.addPastedItems(
                        shoppingList.id(),
                        clientToken,
                        new PasteShoppingListItemsRequest(
                                "  2 x Mleko 1 l  \r\n\r\nHleb\nJogurt x3"
                        )
                );

        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.ignoredBlankLineCount()).isEqualTo(1);
        assertThat(result.items())
                .extracting(ShoppingListItemResponse::name)
                .containsExactly(
                        "Mleko 1 l",
                        "Hleb",
                        "Jogurt"
                );
        assertThat(result.items())
                .extracting(ShoppingListItemResponse::quantity)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(
                        new BigDecimal("2"),
                        BigDecimal.ONE,
                        new BigDecimal("3")
                );
        assertThat(result.items().getFirst().rawInput())
                .isEqualTo("  2 x Mleko 1 l  ");
        assertThat(result.items())
                .extracting(ShoppingListItemResponse::matchingRule)
                .containsOnly(ShoppingItemRule.EXACT_PRODUCT);

        assertThat(shoppingListService.findById(
                shoppingList.id(),
                clientToken
        ).items()).hasSize(3);
    }

    @Test
    void pastedShoppingListRejectsBlankTextAndForeignClient() {
        String ownerToken = "pk045-owner";

        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Privatni spisak"),
                ownerToken
        );

        assertThatThrownBy(() ->
                shoppingListService.addPastedItems(
                        shoppingList.id(),
                        ownerToken,
                        new PasteShoppingListItemsRequest(
                                " \n\t\r\n"
                        )
                )
        ).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("neprazan red");

        assertThatThrownBy(() ->
                shoppingListService.addPastedItems(
                        shoppingList.id(),
                        ownerToken,
                        new PasteShoppingListItemsRequest(
                                "Mleko\n0 x Hleb"
                        )
                )
        ).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("veća od nule");

        assertThatThrownBy(() ->
                shoppingListService.addPastedItems(
                        shoppingList.id(),
                        "pk045-foreign",
                        new PasteShoppingListItemsRequest(
                                "Mleko"
                        )
                )
        ).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Spisak nije pronađen");

        assertThat(shoppingListService.findById(
                shoppingList.id(),
                ownerToken
        ).items()).isEmpty();
    }

    private Long insertStoreWaitingForGeocoding(
            String retailerCode,
            String externalCode,
            String address,
            String city
    ) {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES (?, ?)
                        RETURNING id
                        """)
                .param(1, retailerCode)
                .param(2, retailerCode + " test lanac")
                .query(Long.class)
                .single();

        Long storeFormatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'PILOT', 'Pilot format')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        return jdbcClient.sql("""
                        INSERT INTO app.store (
                            retailer_id,
                            store_format_id,
                            external_code,
                            name,
                            address,
                            city,
                            active
                        )
                        VALUES (?, ?, ?, ?, ?, ?, TRUE)
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, storeFormatId)
                .param(3, externalCode)
                .param(4, externalCode + " test objekat")
                .param(5, address)
                .param(6, city)
                .query(Long.class)
                .single();
    }

    private Long insertVerifiedStore(
            Long retailerId,
            Long storeFormatId,
            String externalCode,
            String name,
            double latitude,
            double longitude,
            boolean active
    ) {
        return jdbcClient.sql("""
                        WITH coordinates AS (
                            SELECT ST_SetSRID(
                                ST_MakePoint(?, ?),
                                4326
                            )::geography AS location
                        )
                        INSERT INTO app.store (
                            retailer_id,
                            store_format_id,
                            external_code,
                            name,
                            address,
                            city,
                            location,
                            active,
                            geocoding_candidate,
                            geocoding_status,
                            geocoding_query,
                            geocoding_source,
                            geocoding_matched_address,
                            geocoding_confidence,
                            geocoded_at,
                            geocoding_review_note,
                            geocoding_reviewed_at
                        )
                        SELECT ?, ?, ?, ?, ?, 'Valjevo',
                               coordinates.location,
                               ?,
                               coordinates.location,
                               'MANUALLY_VERIFIED',
                               LOWER(? || ', Valjevo'),
                               'PK040_TEST',
                               ? || ', Valjevo',
                               1.0000,
                               NOW(),
                               'PK-040 test koordinata',
                               NOW()
                        FROM coordinates
                        RETURNING id
                        """)
                .param(1, longitude)
                .param(2, latitude)
                .param(3, retailerId)
                .param(4, storeFormatId)
                .param(5, externalCode)
                .param(6, name)
                .param(7, name + " adresa")
                .param(8, active)
                .param(9, name + " adresa")
                .param(10, name + " adresa")
                .query(Long.class)
                .single();
    }

    private void assertCanonicalProductInsertFails(
            String canonicalKey,
            String name,
            String barcode,
            Number quantityValue
    ) {
        assertThatThrownBy(() -> insertCanonicalProduct(
                canonicalKey,
                name,
                barcode,
                quantityValue
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertCanonicalProduct(
            String canonicalKey,
            String name,
            String barcode,
            Number quantityValue
    ) {
        jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            barcode,
                            quantity_value
                        )
                        VALUES (?, ?, ?, ?)
                        """)
                .param(1, canonicalKey)
                .param(2, name)
                .param(3, barcode)
                .param(4, quantityValue)
                .update();
    }

    private Long latestImportRunId() {
        return jdbcClient.sql("""
                        SELECT MAX(run.id)
                        FROM app.import_run run
                        JOIN app.retailer retailer
                          ON retailer.id = run.retailer_id
                        WHERE retailer.code = 'TEST'
                        """)
                .query(Long.class)
                .single();
    }

    private Long priceObservationCount() {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.price_observation observation
                        JOIN app.retailer_product product
                          ON product.id = observation.retailer_product_id
                        JOIN app.retailer retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'TEST'
                        """)
                .query(Long.class)
                .single();
    }
}
