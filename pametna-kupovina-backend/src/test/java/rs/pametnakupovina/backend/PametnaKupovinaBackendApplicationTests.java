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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
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
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanBusinessData() {
        jdbcClient.sql("""
                        TRUNCATE TABLE
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
