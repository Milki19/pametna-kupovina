package rs.pametnakupovina.backend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.dataquality.DataQualityReport;
import rs.pametnakupovina.backend.dataquality.DataQualityService;
import rs.pametnakupovina.backend.dataquality.ProductTypeCandidateReview;
import rs.pametnakupovina.backend.dataquality.ProductTypeReviewRequest;
import rs.pametnakupovina.backend.dataquality.ProductTypeReviewResult;
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
import rs.pametnakupovina.backend.priceimport.ImportResult;
import rs.pametnakupovina.backend.priceimport.GovernmentDatasetCatalogRepository;
import rs.pametnakupovina.backend.priceimport.GovernmentPriceDataset;
import rs.pametnakupovina.backend.priceimport.PriceImportService;
import rs.pametnakupovina.backend.priceimport.RetailerDataSourceRepository;
import rs.pametnakupovina.backend.product.CanonicalProductSearchPage;
import rs.pametnakupovina.backend.product.CanonicalProductSearchService;
import rs.pametnakupovina.backend.product.ProductCatalogMaintenanceService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationSource;
import rs.pametnakupovina.backend.retailerlocation.VerifiedRetailerLocation;
import rs.pametnakupovina.backend.shoppinglist.AddShoppingListItemRequest;
import rs.pametnakupovina.backend.shoppinglist.CreateShoppingListRequest;
import rs.pametnakupovina.backend.shoppinglist.FlexibleItemConstraints;
import rs.pametnakupovina.backend.shoppinglist.PasteShoppingListItemsRequest;
import rs.pametnakupovina.backend.shoppinglist.PasteShoppingListItemsResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemMatchingStatus;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemRule;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListItemResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListMatchingResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListMatchingService;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListService;
import rs.pametnakupovina.backend.account.AccountSignInService;
import rs.pametnakupovina.backend.loyalty.LoyaltyCardService;
import rs.pametnakupovina.backend.receipt.ReceiptService;
import rs.pametnakupovina.backend.account.GoogleIdentityVerifier;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListSummary;
import rs.pametnakupovina.backend.shoppinglist.StoreItemOffer;
import rs.pametnakupovina.backend.shoppinglist.StoreShoppingOfferRepository;
import rs.pametnakupovina.backend.shoppinglist.UpdateShoppingListRequest;
import rs.pametnakupovina.backend.store.NearbyStore;
import rs.pametnakupovina.backend.store.NearbyStoreRepository;
import rs.pametnakupovina.backend.store.NearbyStoreService;
import rs.pametnakupovina.backend.store.Store;
import rs.pametnakupovina.backend.store.StoreFormat;
import rs.pametnakupovina.backend.store.StoreRepository;
import rs.pametnakupovina.backend.storepricing.OfficialStorePriceFormat;
import rs.pametnakupovina.backend.storepricing.StorePriceFormatMappingRepository;
import rs.pametnakupovina.backend.storepricing.StorePriceFormatSnapshot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=1",
        "price-import.minimum-snapshot-date="
})
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

    private static final String NEXT_DAY_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            MLEKO;Mlečni proizvodi;Mleko 1 l;Test brend;8600000000004;l;Test format;160;;03-03-2026;160;;;20
            HLEB;Pekarski proizvodi;Beli hleb;Test pekara;8600000000011;kom;Test format;90;;03-03-2026;90;;;20
            """;

    private static final String EXACT_EAN_B_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            NAPICI;Bezalkoholna pića;Pomorandža sok 1000 ml;Test sok;8601234567899;ml;Format B;205;;04-08-2026;205;;;20
            """;

    private static final String UTF16_ALIAS_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinimere;Naziv trgovca - formata*;Datum cenovnika;Redovna cena;Cena po jedinici mere;Snižena cena;Datum početka sniženja;Datum kraja sniženja;stopa PDV
            VODA;Voda;Test voda 1 l;Test brend;8601234500001;kom;Test UTF16 format;21-08-2026;99.99;99.99;;;;20
            """;

    private static final String PRAVILNIK_LIDL_CSV_CONTENT = """
            "KATEGORIJA";"NAZIV KATEGORIJE";"Naziv proizvoda";"Robna marka";"Barkod proizvoda";"Jedinica mere";"Naziv trgovca – formata";"Datum cenovnika";"Redovna cena";"Cena po jedinici mere";"Snizena cena";"Datum pocetka snizenja";"Datum kraja snizenja";"Stopa PDV";"VRSTA_CENOVNIKA"
            1;Mleko;Lidl test mleko 1l;Pilos;4056489000001;kom;Lidl Srbija KD;01-08-2026;129.99;129.99;;;;10;MESECNI_PRESEK
            1;Mleko;Lidl test mleko 1l;Pilos;4056489000001;kom;Lidl Srbija KD;01-09-2026;139.99;139.99;;;;10;MESECNI_PRESEK
            1;Mleko;Lidl test mleko 1l;Pilos;4056489000001;kom;Lidl Srbija KD;01-09-2026;149.99;149.99;;;;10;VAZECI_CENOVNIK
            """;

    private static final String PRAVILNIK_EUROPROM_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca – formata;Datum cenovnika;Redovna cena;Cena po jedinici mere;Snižena cena;Datum početka sniženja;Datum kraja sniženja;Stopa PDV;VRSTA_CENOVNIKA
            1;Mleko;Europrom test jogurt 1kg;Test brend;8601234500100;kom;Europrom;01-09-2026;179.90;179.90;;;;10;MESECNI_PRESEK
            1;Mleko;Europrom test jogurt 1kg;Test brend;8601234500100;kom;Europrom;01-09-2026;189.90;189.90;;;;10;VAZECI_CENOVNIK
            """;

    private static final String PRAVILNIK_UNIVEREXPORT_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata;Datum cenovnika;Redovna cena;Cena po jedinici mere;Snižena cena;Datum početka sniženja;Datum kraja sniženja;Stopa PDV;VRSTA_CENOVNIKA
            3;Hleb;Univerexport test hleb 500g;Test pekara;8601234500209;kom;UNIVEREXPORT - C1-MC1;31-08-2026;79.99;159.98;;;;10;VAZECI_CENOVNIK
            3;Hleb;Univerexport test hleb 500g;Test pekara;8601234500209;kom;UNIVEREXPORT - C3-MC3;31-08-2026;89.99;179.98;;;;10;VAZECI_CENOVNIK
            """;

    private static final String PRAVILNIK_IDEA_CSV_CONTENT = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata;Datum cenovnika;Redovna cena;Cena po jedinici mere;Snižena cena;Datum početka sniženja;Datum kraja sniženja;Stopa PDV;VRSTA_CENOVNIKA
            11;Slatkiši;Idea test keks 150g;Test brend;8601234500308;KOM;IDEA MARKETI_Cenovnik I0;01-08-2026;145.80;972.00;129.99;01-08-2026;15-08-2026;20;MESECNI_PRESEK
            11;Slatkiši;Idea test keks 150g;Test brend;8601234500308;KOM;IDEA MARKETI_Cenovnik I0;01-08-2026;155.80;1038.67;139.99;20-08-2026;05-09-2026;20;VAZECI_CENOVNIK
            10;Grickalice;Idea test flips 150g;Test brend;8601234500407;KOM;IDEA MARKETI_Cenovnik I0;01-08-2026;127.72;851.47
            """;

    private static final String MAXI_STORE_CSV_CONTENT = """
            NAZIV PROIZVODA;ROBNA MARKA;BARKOD PROIZVODA;JEDINICA MERE;PRODAJNA CENA;CENA PO JEDINICI MERE;SNIZENA CENA;DATUM POCETKA PROMOCIJE;DATUM KRAJA PROMOCIJE;VRSTA CENOVNIKA
            Test voda 1 l;Test brend;8600000000004;1;149.99 rsd;149.99 rsd/kom;99.99 rsd;14-AUG-26;13-SEP-26;VAŽEĆI_CENOVNIK
            Test hleb 500 g;Test pekara;8600000000011;.5;79.90 rsd;79.90 rsd/kom;0.00 rsd;;;VAŽEĆI_CENOVNIK
            Neispravan test red;Test brend;8600000000028;1;nije-cena;0.00 rsd/kom;0.00 rsd;;;VAŽEĆI_CENOVNIK
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

    @MockitoBean
    private GoogleIdentityVerifier googleVerifier;

    @Autowired
    private rs.pametnakupovina.backend.crash.CrashReportController crashReportController;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private RetailerDataSourceRepository retailerDataSourceRepository;

    @Autowired
    private GovernmentDatasetCatalogRepository
            governmentDatasetCatalogRepository;

    @Autowired
    private CanonicalProductSearchService canonicalProductSearchService;

    @Autowired
    private ProductCatalogMaintenanceService productCatalogMaintenanceService;

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
    private NearbyStoreRepository nearbyStoreRepository;

    @Autowired
    private DataQualityService dataQualityService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private AccountSignInService accountSignInService;

    @Autowired
    private ReceiptService receiptService;

    @Autowired
    private LoyaltyCardService loyaltyCardService;

    @Autowired
    private ShoppingListMatchingService shoppingListMatchingService;

    @Autowired
    private StoreShoppingOfferRepository storeShoppingOfferRepository;

    @Autowired
    private StorePriceFormatMappingRepository
            storePriceFormatMappingRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private javax.sql.DataSource testDataSource;

    @Test
    void dailyRefreshPersistsEveryOutcomeAndDoesNotCountRepeatedRunsAsDays() {
        var importer = org.mockito.Mockito.mock(PriceImportService.class);
        var maxi = org.mockito.Mockito.mock(rs.pametnakupovina.backend.priceimport.maxi.MaxiPriceImportCoordinator.class);
        var today = LocalDate.now(java.time.ZoneId.of("Europe/Belgrade"));
        var result = new ImportResult(1L,today,2,2,2,0,"SUCCEEDED");
        org.mockito.Mockito.when(importer.importPrices(org.mockito.ArgumentMatchers.anyString())).thenReturn(result);
        var stores = java.util.stream.IntStream.range(0,6).mapToObj(i ->
                new rs.pametnakupovina.backend.priceimport.maxi.MaxiStoreImportResult("store"+i,"test",result,null)).toList();
        org.mockito.Mockito.when(maxi.importLatest()).thenReturn(
                new rs.pametnakupovina.backend.priceimport.maxi.MaxiLatestImportResult(today,6,6,"SUCCEEDED",stores));
        var service = new rs.pametnakupovina.backend.priceimport.DailyPriceRefreshService(
                new org.springframework.jdbc.core.JdbcTemplate(testDataSource),testDataSource,importer,maxi,
                new rs.pametnakupovina.backend.priceimport.ImportRunRecovery(jdbcClient));
        assertThat(service.refresh(true).get("status")).isEqualTo("SUCCEEDED");
        assertThat(service.refresh(true).get("status")).isEqualTo("SUCCEEDED");
        assertThat(service.refresh(false).get("status")).isEqualTo("NOT_DUE");
        assertThat(service.status().get("consecutiveSuccessfulDays")).isEqualTo(1);
        // Five core sources, then Delhaize's catalogue, METRO and Super Vero.
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.price_refresh_result").query(Integer.class).single()).isEqualTo(16);
        org.mockito.Mockito.when(importer.importPrices("METRO")).thenThrow(new IllegalStateException("Test failure"));
        assertThat(service.refresh(true).get("status")).isEqualTo("WARNING");
        org.mockito.Mockito.when(importer.importPrices("LIDL")).thenThrow(new IllegalStateException("Test failure"));
        assertThat(service.refresh(true).get("status")).isEqualTo("FAILED");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.price_refresh_result").query(Integer.class).single()).isEqualTo(32);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.price_refresh_cycle WHERE status='RUNNING'").query(Integer.class).single()).isZero();
    }

    @Test
    void dailyRefreshRefusesOverlapAndRecoversInterruptedCycle() throws Exception {
        var importer = org.mockito.Mockito.mock(PriceImportService.class);
        var maxi = org.mockito.Mockito.mock(rs.pametnakupovina.backend.priceimport.maxi.MaxiPriceImportCoordinator.class);
        var service = new rs.pametnakupovina.backend.priceimport.DailyPriceRefreshService(
                new org.springframework.jdbc.core.JdbcTemplate(testDataSource),testDataSource,importer,maxi,
                new rs.pametnakupovina.backend.priceimport.ImportRunRecovery(jdbcClient));
        jdbcClient.sql("INSERT INTO app.price_refresh_cycle(cycle_date,status) VALUES (CURRENT_DATE,'RUNNING')").update();
        try (var connection = testDataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(134712,1)");
            try {
                assertThat(service.refresh(true).get("status")).isEqualTo("ALREADY_RUNNING");
                assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.price_refresh_cycle WHERE status='RUNNING'").query(Integer.class).single()).isEqualTo(1);
                org.mockito.Mockito.verifyNoInteractions(importer,maxi);
            } finally { statement.execute("SELECT pg_advisory_unlock(134712,1)"); }
        }
        org.mockito.Mockito.when(importer.importPrices(org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("Test failure"));
        org.mockito.Mockito.when(maxi.importLatest()).thenThrow(new IllegalStateException("Test failure"));
        assertThat(service.refresh(true).get("status")).isEqualTo("FAILED");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.price_refresh_cycle WHERE status='FAILED'").query(Integer.class).single()).isEqualTo(2);
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void sharedCatalogRefreshHoldsTransactionLockAndReleasesIt() {
        long retailerId = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES ('LOCK_TEST','Lock test') RETURNING id")
                .query(Long.class).single();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    productCatalogMaintenanceService.refreshRetailer(retailerId);
                    assertThat(jdbcClient.sql("""
                            SELECT count(*) FROM pg_locks
                            WHERE locktype='advisory' AND classid=134711 AND objid=1
                              AND pid=pg_backend_pid() AND granted
                            """).query(Integer.class).single()).isEqualTo(1);
                });
        assertThat(jdbcClient.sql("""
                SELECT count(*) FROM pg_locks
                WHERE locktype='advisory' AND classid=134711 AND objid=1 AND granted
                """).query(Integer.class).single()).isZero();
        productCatalogMaintenanceService.refreshAll();
        assertThat(jdbcClient.sql("""
                SELECT count(*) FROM pg_locks
                WHERE locktype='advisory' AND classid=134711 AND objid=1 AND granted
                """).query(Integer.class).single()).isZero();
    }

    @BeforeEach
    void cleanBusinessData() {
        jdbcClient.sql("""
                        TRUNCATE TABLE
                            app.loyalty_card,
                            app.receipt_item,
                            app.receipt,
                            app.product_merge_suggestion,
                            app.product_merge_decision,
                            app.product_report,
                            app.price_list_snapshot,
                            app.price_refresh_result,
                            app.price_refresh_cycle,
                            app.government_dataset_candidate,
                            app.shopping_list_item,
                            app.shopping_list,
                            app.product_match_feedback,
                            app.product_match_decision,
                            app.product_identity_candidate,
                            app.product_family_typical_price,
                            app.product_retailer_presence,
                            app.product_family_member,
                            app.retailer_product_attribute,
                            app.product_type_candidate,
                            app.retailer_product_type_rejection,
                            app.retailer_product_type,
                            app.retailer_product_category,
                            app.current_price_offer,
                            app.price_observation,
                            app.retailer_product,
                            app.product_family,
                            app.import_run,
                            app.store_price_format_mapping,
                            app.store,
                            app.store_format,
                            app.canonical_product,
                            app.brand_alias,
                            app.brand,
                            app.retailer_data_source,
                            app.account_identity,
                            app.account_device,
                            app.account
                        RESTART IDENTITY
                        """)
                .update();

        jdbcClient.sql("DELETE FROM app.retailer").update();
    }

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        csvServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

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

        csvServer.createContext("/prices-next-day.csv", exchange -> {
            byte[] responseBody = NEXT_DAY_CSV_CONTENT
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

        csvServer.createContext("/utf16-alias.csv", exchange -> {
            byte[] content = UTF16_ALIAS_CSV_CONTENT
                    .getBytes(StandardCharsets.UTF_16LE);
            byte[] responseBody = new byte[content.length + 2];
            responseBody[0] = (byte) 0xFF;
            responseBody[1] = (byte) 0xFE;
            System.arraycopy(
                    content,
                    0,
                    responseBody,
                    2,
                    content.length
            );

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/csv; charset=UTF-16LE"
            );

            exchange.sendResponseHeaders(200, responseBody.length);

            try (OutputStream outputStream =
                         exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        });

        csvServer.createContext("/pravilnik-lidl.csv", exchange -> {
            byte[] responseBody = PRAVILNIK_LIDL_CSV_CONTENT
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

        csvServer.createContext("/pravilnik-europrom.csv", exchange -> {
            byte[] content = PRAVILNIK_EUROPROM_CSV_CONTENT
                    .getBytes(StandardCharsets.UTF_8);
            byte[] responseBody = new byte[content.length + 3];
            responseBody[0] = (byte) 0xEF;
            responseBody[1] = (byte) 0xBB;
            responseBody[2] = (byte) 0xBF;
            System.arraycopy(
                    content,
                    0,
                    responseBody,
                    3,
                    content.length
            );
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

        csvServer.createContext("/pravilnik-univerexport.csv", exchange -> {
            byte[] responseBody = PRAVILNIK_UNIVEREXPORT_CSV_CONTENT
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

        csvServer.createContext("/pravilnik-idea.csv", exchange -> {
            byte[] content = PRAVILNIK_IDEA_CSV_CONTENT
                    .getBytes(StandardCharsets.UTF_16LE);
            byte[] responseBody = new byte[content.length + 2];
            responseBody[0] = (byte) 0xFF;
            responseBody[1] = (byte) 0xFE;
            System.arraycopy(
                    content,
                    0,
                    responseBody,
                    2,
                    content.length
            );
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/csv; charset=UTF-16LE"
            );
            exchange.sendResponseHeaders(200, responseBody.length);

            try (OutputStream outputStream =
                         exchange.getResponseBody()) {
                outputStream.write(responseBody);
            }
        });

        csvServer.createContext("/maxi-store.csv", exchange -> {
            byte[] responseBody = MAXI_STORE_CSV_CONTENT
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

        csvServer.createContext("/stalled.csv", exchange -> {
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "text/csv; charset=UTF-8"
            );
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write("KATEGORIJA;Naziv proizvoda\n".getBytes(
                        StandardCharsets.UTF_8
                ));
                outputStream.flush();
                Thread.sleep(3000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        csvServer.createContext(
                "/api/1/datasets/discovery-test/",
                exchange -> {
                    String baseUrl = "http://127.0.0.1:"
                            + csvServer.getAddress().getPort();
                    byte[] responseBody = ("""
                            {
                              "resources": [
                                {
                                  "format": "xlsx",
                                  "url": "%s/locations.xlsx",
                                  "last_modified": "2026-08-26T05:00:00Z"
                                },
                                {
                                  "format": "csv",
                                  "title": "cene-proizvoda-test.csv",
                                  "url": "%s/prices-next-day.csv",
                                  "last_modified": "2026-08-26T04:00:00Z"
                                }
                              ]
                            }
                            """).formatted(baseUrl, baseUrl)
                            .getBytes(StandardCharsets.UTF_8);

                    exchange.getResponseHeaders().set(
                            "Content-Type",
                            "application/json; charset=UTF-8"
                    );
                    exchange.sendResponseHeaders(200, responseBody.length);

                    try (OutputStream outputStream =
                                 exchange.getResponseBody()) {
                        outputStream.write(responseBody);
                    }
                }
        );

        csvServer.start();
    }

    @Test
    void runtimeConnectionsCanResolveApplicationAndPostgisSchemas() {
        String searchPath = jdbcClient.sql("SHOW search_path")
                .query(String.class)
                .single();

        assertThat(searchPath).contains("app").contains("public");
        assertThat(jdbcClient.sql("SELECT postgis_version()")
                .query(String.class)
                .single()).isNotBlank();
    }

    @Test
    void catalogCandidateRefreshPreservesManualReviewStatus() {
        Instant firstVersion = Instant.parse("2026-09-01T08:00:00Z");
        Instant secondVersion = Instant.parse("2026-09-05T10:30:00Z");

        governmentDatasetCatalogRepository.upsertAll(List.of(
                new GovernmentPriceDataset(
                        "dataset-1",
                        "test-cenovnik",
                        "Test cenovnik",
                        "Test trgovac",
                        "https://data.gov.rs/sr/datasets/test-cenovnik/",
                        "resource-1",
                        "Cenovnik 1. septembar",
                        "https://example.test/cenovnik-1.csv",
                        "CSV",
                        firstVersion
                )
        ));

        jdbcClient.sql("""
                        UPDATE app.government_dataset_candidate
                        SET review_status = 'APPROVED'
                        WHERE portal_dataset_id = 'dataset-1'
                        """).update();

        governmentDatasetCatalogRepository.upsertAll(List.of(
                new GovernmentPriceDataset(
                        "dataset-1",
                        "test-cenovnik",
                        "Test cenovnik - osvežen",
                        "Test trgovac",
                        "https://data.gov.rs/sr/datasets/test-cenovnik/",
                        "resource-2",
                        "Cenovnik 5. septembar",
                        "https://example.test/cenovnik-2.csv",
                        "CSV",
                        secondVersion
                )
        ));

        assertThat(governmentDatasetCatalogRepository.findAll())
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.portalDatasetId())
                            .isEqualTo("dataset-1");
                    assertThat(candidate.title())
                            .isEqualTo("Test cenovnik - osvežen");
                    assertThat(candidate.resourceId())
                            .isEqualTo("resource-2");
                    assertThat(candidate.resourceLastModified())
                            .isEqualTo(secondVersion);
                    assertThat(candidate.reviewStatus())
                            .isEqualTo("APPROVED");
                });
    }

    @Test
    void stalledDownloadFailsWithinDeadlineAndUpdatesSourceHealth() {
        String datasetUrl = "http://127.0.0.1:"
                + csvServer.getAddress().getPort()
                + "/stalled.csv";
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('STALL_TEST', 'Stall test', ?)
                        RETURNING id
                        """)
                .param(1, datasetUrl)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                    INSERT INTO app.retailer_data_source (
                        retailer_id,
                        code,
                        source_type,
                        parser_profile,
                        source_url,
                        price_scope
                    )
                    VALUES (
                        ?,
                        'PRIMARY_PRICE_CATALOG',
                        'PRICE_CATALOG',
                        'GOV_RS_SEMICOLON_CSV',
                        ?,
                        'RETAILER_OR_FORMAT'
                    )
                    """)
                .param(1, retailerId)
                .param(2, datasetUrl)
                .update();

        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> priceImportService.importPrices("STALL_TEST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prekoračilo rok");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
                .isLessThan(Duration.ofSeconds(3));
        assertThat(jdbcClient.sql("""
                        SELECT run.status || ':' || run.stage
                        FROM app.import_run AS run
                        ORDER BY run.id DESC
                        LIMIT 1
                        """)
                .query(String.class)
                .single()).isEqualTo("FAILED:FAILED");
        assertThat(jdbcClient.sql("""
                        SELECT source.last_status || ':' ||
                               source.consecutive_failure_count
                        FROM app.retailer_data_source AS source
                        WHERE source.retailer_id = ?
                        """)
                .param(1, retailerId)
                .query(String.class)
                .single()).isEqualTo("FAILED:1");
    }

    @Test
    void qualityReportFlagsBrokenSourceAndReviewsIneligibleStore() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('QUALITY_TEST', 'Quality test')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        Long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'QUALITY', 'Quality')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();
        Long storeId = insertVerifiedStore(
                retailerId,
                formatId,
                "QUALITY-1",
                "Quality prodavnica",
                44.274,
                19.880,
                true
        );
        jdbcClient.sql("""
                    UPDATE app.store
                    SET pricing_eligible = FALSE,
                        pricing_ineligibility_reason =
                            'PRICE_FORMAT_NOT_VERIFIED'
                    WHERE id = ?
                    """)
                .param(1, storeId)
                .update();
        jdbcClient.sql("""
                    INSERT INTO app.retailer_data_source (
                        retailer_id,
                        code,
                        source_type,
                        parser_profile,
                        source_url,
                        active,
                        last_status,
                        expected_min_rows_saved,
                        max_success_age_hours,
                        last_rows_saved,
                        consecutive_failure_count
                    )
                    VALUES (
                        ?, 'BROKEN_SOURCE', 'PRICE_CATALOG', 'TEST',
                        'https://example.test/prices.csv', TRUE, 'FAILED',
                        100, 48, 5, 1
                    )
                    """)
                .param(1, retailerId)
                .update();
        jdbcClient.sql("""
                    INSERT INTO app.product_family (
                        family_key,
                        display_name,
                        normalized_name,
                        review_status
                    )
                    VALUES (
                        'QUALITY-DUPLICATE',
                        'Sumnjivi duplikat',
                        'sumnjivi duplikat',
                        'REVIEW_REQUIRED'
                    )
                    """)
                .update();

        DataQualityReport report = dataQualityService.report();

        assertThat(report.status()).isEqualTo("CRITICAL");
        assertThat(report.summary().suspectedDuplicateProductFamilies())
                .isEqualTo(1);
        assertThat(report.locations().pricingIneligible()).isEqualTo(1);
        assertThat(report.sources()).singleElement().satisfies(source -> {
            assertThat(source.health()).isEqualTo("CRITICAL");
            assertThat(source.alerts()).contains(
                    "LAST_RUN_FAILED",
                    "SOURCE_DATA_STALE",
                    "ROW_COUNT_BELOW_MINIMUM"
            );
        });
        assertThat(dataQualityService.reviewLocations(true, 10))
                .singleElement()
                .satisfies(store -> {
                    assertThat(store.storeId()).isEqualTo(storeId);
                    assertThat(store.pricingIneligibilityReason())
                            .isEqualTo("PRICE_FORMAT_NOT_VERIFIED");
                });
    }

    @Test
    void pricingRecommendationsIgnoreIneligibleNearbyStores() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('ELIGIBILITY_TEST', 'Eligibility test')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        Long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'STANDARD', 'Standard')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();
        Long eligibleId = insertVerifiedStore(
                retailerId,
                formatId,
                "ELIGIBLE",
                "Podobna",
                44.274,
                19.880,
                true
        );
        Long ineligibleId = insertVerifiedStore(
                retailerId,
                formatId,
                "INELIGIBLE",
                "Nepodobna",
                44.2741,
                19.8801,
                true
        );
        jdbcClient.sql("""
                    UPDATE app.store
                    SET pricing_eligible = FALSE,
                        pricing_ineligibility_reason =
                            'PRICE_FORMAT_NOT_VERIFIED'
                    WHERE id = ?
                    """)
                .param(1, ineligibleId)
                .update();

        assertThat(nearbyStoreRepository.findPricingEligibleNearby(
                44.274,
                19.880,
                1000,
                10
        )).extracting(NearbyStore::storeId).containsExactly(eligibleId);
        assertThat(nearbyStoreRepository.findNearby(
                44.274,
                19.880,
                1000,
                10
        )).extracting(NearbyStore::storeId)
                .containsExactly(eligibleId, ineligibleId);
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

        Long currentRowsPointingToSecondImport = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.current_price_offer
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

        assertThat(rowsPointingToFirstImport).isEqualTo(2);
        assertThat(rowsPointingToSecondImport).isZero();
        assertThat(currentRowsPointingToSecondImport).isEqualTo(2);

        assertThat(importStatuses)
                .containsExactly("SUCCEEDED", "SUCCEEDED");

        assertThat(normalizedMilkName).isEqualTo("mleko 1 l");
        assertThat(milkQuantity).isEqualByComparingTo("1000");
        assertThat(milkBaseUnit).isEqualTo("ml");
    }

    @Test
    void currentOffersAdvanceDailyButHistoryStoresOnlyChanges() {
        String firstDatasetUrl =
                "http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/prices.csv";
        String nextDatasetUrl =
                "http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/prices-next-day.csv";

        jdbcClient.sql("""
                        INSERT INTO app.retailer (
                            code,
                            name,
                            dataset_url
                        )
                        VALUES ('PRICE_CHANGE_TEST', 'Price change test', ?)
                        """)
                .param(1, firstDatasetUrl)
                .update();

        priceImportService.importPrices("PRICE_CHANGE_TEST");

        jdbcClient.sql("""
                    UPDATE app.retailer
                    SET dataset_url = ?
                    WHERE code = 'PRICE_CHANGE_TEST'
                    """)
                .param(1, nextDatasetUrl)
                .update();

        priceImportService.importPrices("PRICE_CHANGE_TEST");

        Long currentCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.current_price_offer AS current_offer
                        JOIN app.retailer_product AS product
                          ON product.id = current_offer.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'PRICE_CHANGE_TEST'
                        """)
                .query(Long.class)
                .single();

        Long historyCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.price_observation AS history
                        JOIN app.retailer_product AS product
                          ON product.id = history.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'PRICE_CHANGE_TEST'
                        """)
                .query(Long.class)
                .single();

        List<String> seenRanges = jdbcClient.sql("""
                        SELECT product.name || ':' ||
                               current_offer.first_seen_date || ':' ||
                               current_offer.last_seen_date
                        FROM app.current_price_offer AS current_offer
                        JOIN app.retailer_product AS product
                          ON product.id = current_offer.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'PRICE_CHANGE_TEST'
                        ORDER BY product.name
                        """)
                .query(String.class)
                .list();

        Long controlledAssignments = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.retailer_product_category AS assignment
                        JOIN app.retailer_product AS product
                          ON product.id = assignment.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'PRICE_CHANGE_TEST'
                        """)
                .query(Long.class)
                .single();

        assertThat(currentCount).isEqualTo(2);
        assertThat(historyCount).isEqualTo(3);
        assertThat(seenRanges).containsExactly(
                "Beli hleb:2026-03-03:2026-03-03",
                "Mleko 1 l:2026-03-02:2026-03-03"
        );
        assertThat(controlledAssignments).isEqualTo(2);
    }

    @Test
    void registeredPriceSourceRejectsConcurrentImport() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('IMPORT_LOCK_TEST', 'Import lock test')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long sourceId = jdbcClient.sql("""
                        INSERT INTO app.retailer_data_source (
                            retailer_id,
                            code,
                            source_type,
                            parser_profile,
                            source_url,
                            price_scope,
                            active
                        )
                        VALUES (
                            ?,
                            'PRIMARY_PRICE_CATALOG',
                            'PRICE_CATALOG',
                            'GOV_RS_SEMICOLON_CSV',
                            'http://127.0.0.1/prices.csv',
                            'RETAILER_OR_FORMAT',
                            TRUE
                        )
                        RETURNING id
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();

        assertThat(
                retailerDataSourceRepository.tryMarkRunning(sourceId)
        ).isTrue();
        assertThat(
                retailerDataSourceRepository.tryMarkRunning(sourceId)
        ).isFalse();

        jdbcClient.sql("""
                    UPDATE app.retailer_data_source
                    SET last_started_at = NOW() - INTERVAL '7 hours'
                    WHERE id = ?
                    """)
                .param(1, sourceId)
                .update();

        assertThat(
                retailerDataSourceRepository.tryMarkRunning(sourceId)
        ).isTrue();
    }

    @Test
    void importDiscoversLatestGovernmentCsvBeforeDownload() {
        String baseUrl = "http://127.0.0.1:"
                + csvServer.getAddress().getPort();

        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (
                            code,
                            name,
                            dataset_url
                        )
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "DISCOVERY_TEST")
                .param(2, "Discovery test")
                .param(3, baseUrl + "/prices.csv")
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                    INSERT INTO app.retailer_data_source (
                        retailer_id,
                        code,
                        source_type,
                        parser_profile,
                        source_url,
                        discovery_url,
                        price_scope,
                        active
                    )
                    VALUES (?, ?, 'PRICE_CATALOG', ?, ?, ?, ?, TRUE)
                    """)
                .param(1, retailerId)
                .param(2, "PRIMARY_PRICE_CATALOG")
                .param(3, "GOV_RS_SEMICOLON_CSV")
                .param(4, baseUrl + "/prices.csv")
                .param(
                        5,
                        baseUrl + "/sr/datasets/discovery-test/"
                )
                .param(6, "RETAILER_OR_FORMAT")
                .update();

        ImportResult result = priceImportService.importPrices(
                "DISCOVERY_TEST"
        );

        String resolvedUrl = jdbcClient.sql("""
                        SELECT source_url
                        FROM app.retailer_data_source
                        WHERE retailer_id = ?
                        """)
                .param(1, retailerId)
                .query(String.class)
                .single();

        assertThat(result.snapshotDate()).isEqualTo(
                LocalDate.of(2026, 3, 3)
        );
        assertThat(resolvedUrl).isEqualTo(
                baseUrl + "/prices-next-day.csv"
        );
    }

    @Test
    void importSupportsUtf16AndKnownHeaderAliases() {
        String datasetUrl =
                "http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/utf16-alias.csv";

        jdbcClient.sql("""
                        INSERT INTO app.retailer (
                            code,
                            name,
                            dataset_url
                        )
                        VALUES (?, ?, ?)
                        """)
                .param(1, "UTF16_ALIAS")
                .param(2, "UTF16 alias prodavnica")
                .param(3, datasetUrl)
                .update();

        ImportResult result = priceImportService.importPrices(
                "UTF16_ALIAS"
        );

        String importedUnit = jdbcClient.sql("""
                        SELECT product.unit
                        FROM app.retailer_product product
                        JOIN app.retailer retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'UTF16_ALIAS'
                        """)
                .query(String.class)
                .single();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.snapshotDate())
                .isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(result.rowsRead()).isEqualTo(1);
        assertThat(result.rowsSelected()).isEqualTo(1);
        assertThat(result.rowsSaved()).isEqualTo(1);
        assertThat(importedUnit).isEqualTo("kom");
    }

    @Test
    void importsLidlPravilnikCatalogWithoutMonthlyDuplicates() {
        registerPriceTestRetailer(
                "PRAVILNIK_LIDL",
                "/pravilnik-lidl.csv"
        );

        ImportResult result = priceImportService.importPrices(
                "PRAVILNIK_LIDL"
        );

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.snapshotDate())
                .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(result.rowsRead()).isEqualTo(3);
        assertThat(result.rowsSelected()).isEqualTo(1);
        assertThat(result.rowsSaved()).isEqualTo(1);
        assertThat(currentRegularPrice(
                "PRAVILNIK_LIDL",
                "4056489000001",
                "Lidl Srbija KD"
        )).isEqualByComparingTo("149.99");
    }

    @Test
    void importsEuropromPravilnikCatalogWithUtf8Bom() {
        registerPriceTestRetailer(
                "PRAVILNIK_EUROPROM",
                "/pravilnik-europrom.csv"
        );

        ImportResult result = priceImportService.importPrices(
                "PRAVILNIK_EUROPROM"
        );

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.rowsRead()).isEqualTo(2);
        assertThat(result.rowsSelected()).isEqualTo(1);
        assertThat(currentRegularPrice(
                "PRAVILNIK_EUROPROM",
                "8601234500100",
                "Europrom"
        )).isEqualByComparingTo("189.90");
    }

    @Test
    void importsAllCurrentUniverexportFormats() {
        registerPriceTestRetailer(
                "PRAVILNIK_UNIVEREXPORT",
                "/pravilnik-univerexport.csv"
        );

        ImportResult result = priceImportService.importPrices(
                "PRAVILNIK_UNIVEREXPORT"
        );

        List<String> offers = jdbcClient.sql("""
                        SELECT offer.retailer_format_name
                               || ':' || offer.regular_price
                        FROM app.current_price_offer AS offer
                        JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'PRAVILNIK_UNIVEREXPORT'
                        ORDER BY offer.retailer_format_name
                        """)
                .query(String.class)
                .list();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.rowsSelected()).isEqualTo(2);
        assertThat(result.rowsSaved()).isEqualTo(2);
        assertThat(offers).containsExactly(
                "UNIVEREXPORT - C1-MC1:79.99",
                "UNIVEREXPORT - C3-MC3:89.99"
        );
    }

    @Test
    void importsUtf16IdeaCatalogWithTruncatedOptionalColumns() {
        registerPriceTestRetailer(
                "PRAVILNIK_IDEA",
                "/pravilnik-idea.csv"
        );

        ImportResult result = priceImportService.importPrices(
                "PRAVILNIK_IDEA"
        );

        List<String> offers = jdbcClient.sql("""
                        SELECT product.barcode || ':'
                               || offer.regular_price || ':'
                               || COALESCE(offer.vat_rate::TEXT, 'NULL')
                        FROM app.current_price_offer AS offer
                        JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = 'PRAVILNIK_IDEA'
                        ORDER BY product.barcode
                        """)
                .query(String.class)
                .list();

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.rowsRead()).isEqualTo(3);
        assertThat(result.rowsSelected()).isEqualTo(2);
        assertThat(result.rowsSaved()).isEqualTo(2);
        assertThat(offers).containsExactly(
                "8601234500308:155.80:20.00",
                "8601234500407:127.72:NULL"
        );
    }

    @Test
    void importsStoreScopedMaxiPricesAndPersistsPartialSuccessStatus() {
        String datasetUrl =
                "http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/maxi-store.csv";

        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('MAXI', 'Maxi')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'MAXI', 'Maxi')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();

        Long storeId = jdbcClient.sql("""
                        INSERT INTO app.store (
                            retailer_id,
                            external_code,
                            name,
                            address,
                            city,
                            location,
                            store_format_id
                        )
                        VALUES (
                            ?,
                            '508',
                            'Maxi 508',
                            'Kneza Mihaila 84-86',
                            'Valjevo',
                            ST_SetSRID(
                                ST_MakePoint(19.891145, 44.263836),
                                4326
                            )::geography,
                            ?
                        )
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, formatId)
                .query(Long.class)
                .single();

        ImportResult result = priceImportService.importStorePrices(
                "maxi",
                "508",
                datasetUrl,
                LocalDate.of(2026, 8, 21)
        );

        Long scopedObservationCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.price_observation
                        WHERE store_id = ?
                        """)
                .param(1, storeId)
                .query(Long.class)
                .single();

        BigDecimal discountedPrice = jdbcClient.sql("""
                        SELECT observation.discounted_price
                        FROM app.price_observation AS observation
                        JOIN app.retailer_product AS product
                          ON product.id = observation.retailer_product_id
                        WHERE observation.store_id = ?
                          AND product.barcode = '8600000000004'
                        """)
                .param(1, storeId)
                .query(BigDecimal.class)
                .single();

        String storedMetadata = jdbcClient.sql("""
                        SELECT product.brand || ':'
                               || observation.discount_start || ':'
                               || observation.discount_end
                        FROM app.price_observation AS observation
                        JOIN app.retailer_product AS product
                          ON product.id = observation.retailer_product_id
                        WHERE observation.store_id = ?
                          AND product.barcode = '8600000000004'
                        """)
                .param(1, storeId)
                .query(String.class)
                .single();

        Long canonicalProductCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.retailer_product
                        WHERE retailer_id = ?
                          AND canonical_product_id IS NOT NULL
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();

        List<String> categoryAssignments = jdbcClient.sql("""
                        SELECT product.name || ':' || category.code
                        FROM app.retailer_product AS product
                        JOIN app.retailer_product_category AS assignment
                          ON assignment.retailer_product_id = product.id
                        JOIN app.product_category AS category
                          ON category.id = assignment.product_category_id
                        WHERE product.retailer_id = ?
                        ORDER BY product.name
                        """)
                .param(1, retailerId)
                .query(String.class)
                .list();

        BigDecimal breadMinimumPrice = jdbcClient.sql("""
                        SELECT presence.minimum_effective_price
                        FROM app.product_retailer_presence AS presence
                        JOIN app.retailer_product AS product
                          ON product.product_family_id =
                             presence.product_family_id
                         AND product.retailer_id = presence.retailer_id
                        WHERE product.retailer_id = ?
                          AND product.barcode = '8600000000011'
                        """)
                .param(1, retailerId)
                .query(BigDecimal.class)
                .single();

        String persistedStatus = jdbcClient.sql("""
                        SELECT status
                        FROM app.import_run
                        WHERE id = ?
                        """)
                .param(1, result.importRunId())
                .query(String.class)
                .single();

        assertThat(result.status()).isEqualTo("SUCCEEDED_WITH_ERRORS");
        assertThat(persistedStatus).isEqualTo("SUCCEEDED_WITH_ERRORS");
        assertThat(result.snapshotDate())
                .isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(result.rowsRead()).isEqualTo(3);
        assertThat(result.rowsSelected()).isEqualTo(2);
        assertThat(result.rowsSaved()).isEqualTo(2);
        assertThat(scopedObservationCount).isEqualTo(2);
        assertThat(discountedPrice).isEqualByComparingTo("99.99");
        assertThat(storedMetadata)
                .isEqualTo("Test brend:2026-08-14:2026-09-13");
        assertThat(canonicalProductCount).isEqualTo(2);
        assertThat(categoryAssignments).containsExactly(
                "Test hleb 500 g:BREAD",
                "Test voda 1 l:WATER"
        );
        assertThat(breadMinimumPrice).isEqualByComparingTo("79.90");
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

        jdbcClient.sql("""
                        INSERT INTO app.brand (
                            normalized_name,
                            display_name
                        )
                        VALUES ('imlek', 'Imlek')
                        ON CONFLICT (normalized_name) DO NOTHING
                        """)
                .update();

        jdbcClient.sql("""
                        UPDATE app.canonical_product
                        SET brand_id = (
                            SELECT id
                            FROM app.brand
                            WHERE normalized_name = 'imlek'
                        )
                        WHERE canonical_key LIKE 'PK035-CATALOG-%'
                        """)
                .update();

        jdbcClient.sql("""
                        INSERT INTO app.product_family (
                            family_key,
                            display_name,
                            normalized_name,
                            brand_id,
                            quantity_value,
                            base_unit
                        )
                        SELECT 'TEST:' || product.id,
                               product.name,
                               product.normalized_name,
                               product.brand_id,
                               product.quantity_value,
                               product.base_unit
                        FROM app.canonical_product AS product
                        WHERE product.canonical_key LIKE 'PK035-CATALOG-%'
                        """)
                .update();

        jdbcClient.sql("""
                        INSERT INTO app.product_family_member (
                            family_id,
                            canonical_product_id,
                            relation_type,
                            confidence
                        )
                        SELECT family.id,
                               product.id,
                               'SINGLE_GTIN',
                               1.0000
                        FROM app.canonical_product AS product
                        JOIN app.product_family AS family
                          ON family.family_key = 'TEST:' || product.id
                        WHERE product.canonical_key LIKE 'PK035-CATALOG-%'
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
                        WHERE client_token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')
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
    void verifiedLocationSyncStoresProvenanceAndDeactivatesMissingRows() {
        jdbcClient.sql("""
                    INSERT INTO app.retailer (code, name)
                    VALUES ('OFFICIAL_LOCATION_TEST', 'Official locations')
                    """)
                .update();

        RetailerLocationSource source = new RetailerLocationSource(
                "OFFICIAL_LOCATION_API",
                "TEST_LOCATION_JSON",
                "https://example.test/locations",
                "OFFICIAL_RETAILER_API",
                true,
                null
        );
        VerifiedRetailerLocation first = new VerifiedRetailerLocation(
                "001",
                "Prvi objekat",
                "Prva 1",
                "Valjevo",
                "STANDARD",
                "Standard",
                44.274,
                19.880,
                true
        );
        VerifiedRetailerLocation second = new VerifiedRetailerLocation(
                "002",
                "Drugi objekat",
                "Druga 2",
                "Valjevo",
                "STANDARD",
                "Standard",
                44.275,
                19.881,
                true
        );

        retailerLocationImportService.importVerifiedLocations(
                "OFFICIAL_LOCATION_TEST",
                List.of(first, second),
                source
        );
        RetailerLocationImportResult secondSync =
                retailerLocationImportService.importVerifiedLocations(
                        "OFFICIAL_LOCATION_TEST",
                        List.of(second),
                        source
                );

        List<String> storeStates = jdbcClient.sql("""
                        SELECT store.external_code || ':' ||
                               store.active || ':' ||
                               store.pricing_eligible || ':' ||
                               store.geocoding_source || ':' ||
                               store.geocoding_source_reference
                        FROM app.store AS store
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        WHERE retailer.code = 'OFFICIAL_LOCATION_TEST'
                        ORDER BY store.external_code
                        """)
                .query(String.class)
                .list();
        String registeredSourceUrl = jdbcClient.sql("""
                        SELECT source.source_url
                        FROM app.retailer_data_source AS source
                        JOIN app.retailer AS retailer
                          ON retailer.id = source.retailer_id
                        WHERE retailer.code = 'OFFICIAL_LOCATION_TEST'
                          AND source.code = 'OFFICIAL_LOCATION_API'
                        """)
                .query(String.class)
                .single();

        assertThat(secondSync.status()).isEqualTo("SUCCEEDED");
        assertThat(storeStates).containsExactly(
                "001:false:false:OFFICIAL_RETAILER_API:https://example.test/locations",
                "002:true:true:OFFICIAL_RETAILER_API:https://example.test/locations"
        );
        assertThat(registeredSourceUrl)
                .isEqualTo("https://example.test/locations");
    }

    @Test
    void duplicateOfficialCoordinatesAreExcludedFromPricing() {
        jdbcClient.sql("""
                    INSERT INTO app.retailer (code, name)
                    VALUES ('DUPLICATE_LOCATION_TEST', 'Duplicate locations')
                    """)
                .update();

        RetailerLocationSource source = new RetailerLocationSource(
                "DUPLICATE_LOCATION_API",
                "TEST_LOCATION_JSON",
                "https://example.test/locations",
                "OFFICIAL_RETAILER_API",
                true,
                null
        );
        VerifiedRetailerLocation first = new VerifiedRetailerLocation(
                "001",
                "Prvi objekat",
                "Prva 1",
                "Valjevo",
                "STANDARD",
                "Standard",
                44.274,
                19.880,
                true
        );
        VerifiedRetailerLocation second = new VerifiedRetailerLocation(
                "002",
                "Drugi objekat",
                "Druga 2",
                "Valjevo",
                "STANDARD",
                "Standard",
                44.274,
                19.880,
                true
        );

        retailerLocationImportService.importVerifiedLocations(
                "DUPLICATE_LOCATION_TEST",
                List.of(first, second),
                source
        );

        List<String> pricingStates = jdbcClient.sql("""
                        SELECT store.external_code || ':' ||
                               store.pricing_eligible || ':' ||
                               store.pricing_ineligibility_reason
                        FROM app.store AS store
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        WHERE retailer.code = 'DUPLICATE_LOCATION_TEST'
                        ORDER BY store.external_code
                        """)
                .query(String.class)
                .list();

        assertThat(pricingStates).containsExactly(
                "001:false:DUPLICATE_OFFICIAL_COORDINATES",
                "002:false:DUPLICATE_OFFICIAL_COORDINATES"
        );
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
    void knownFlexibleIntentIsImmediatelyReadyForOptimization() {
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
                .isEqualTo(ShoppingItemMatchingStatus.CONFIRMED);
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
                                    ShoppingItemMatchingStatus.CONFIRMED
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

        // Uređaj se od V95 vodi uz nalog, a spisak pripada nalogu; sam broj
        // uređaja se i dalje nigde ne čuva, samo njegov otisak.
        String storedTokenHash = jdbcClient.sql("""
                        SELECT device.client_token_hash
                        FROM app.shopping_list AS list
                        JOIN app.account_device AS device
                          ON device.account_id = list.account_id
                        WHERE list.id = ?
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
        // "2 x Mleko 1 l" is two litres of any milk, read the same way as
        // "ćevapi 3kg": the size is an amount, not part of a product name.
        assertThat(result.items())
                .extracting(ShoppingListItemResponse::name)
                .containsExactly(
                        "Mleko",
                        "Hleb",
                        "Jogurt"
                );
        assertThat(result.items().getFirst().flexibleConstraints().targetQuantity())
                .isEqualByComparingTo("1000");
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
                .containsExactly(
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        ShoppingItemRule.FLEXIBLE_CATEGORY
                );
        assertThat(result.items())
                .extracting(ShoppingListItemResponse::matchingStatus)
                .containsExactly(
                        ShoppingItemMatchingStatus.CONFIRMED,
                        ShoppingItemMatchingStatus.CONFIRMED,
                        ShoppingItemMatchingStatus.CONFIRMED
                );

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

    @Test
    void flexibleItemPersistsCategoryAndPackageConstraints() {
        String clientToken = "pk047-flexible-owner";

        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Fleksibilna korpa"),
                clientToken
        );

        ShoppingListItemResponse item = shoppingListService.addItem(
                shoppingList.id(),
                clientToken,
                new AddShoppingListItemRequest(
                        "Bilo koje mleko",
                        "Bilo koje mleko 1 l",
                        null,
                        BigDecimal.ONE,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints(
                                "Mleko",
                                "Imlek",
                                new BigDecimal("500"),
                                new BigDecimal("1500"),
                                "ml"
                        )
                )
        );

        assertThat(item.flexibleConstraints()).isNotNull();
        assertThat(item.flexibleConstraints().category())
                .isEqualTo("Mleko");
        assertThat(item.flexibleConstraints().requiredBrand())
                .isEqualTo("Imlek");
        assertThat(item.flexibleConstraints().minPackageQuantity())
                .isEqualByComparingTo("500");
        assertThat(item.flexibleConstraints().maxPackageQuantity())
                .isEqualByComparingTo("1500");
        assertThat(item.flexibleConstraints().requiredBaseUnit())
                .isEqualTo("ml");

        assertThat(jdbcClient.sql("""
                        SELECT flexible_category_normalized
                        FROM app.shopping_list_item
                        WHERE id = ?
                        """)
                .param(1, item.id())
                .query(String.class)
                .single()).isEqualTo("mleko");
    }

    @Test
    void shoppingListMatchingAutomaticallyLinksStrongCandidate() {
        String clientToken = "pk046-matching-owner";

        Long canonicalProductId = jdbcClient.sql("""
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
                .param(1, "PK046-AUTO-MILK")
                .param(2, "PK046 Imlek mleko 1 l")
                .param(3, "pk 046 imlek mleko 1 l")
                .param(4, "Imlek")
                .param(5, 1000)
                .param(6, "ml")
                .query(Long.class)
                .single();

        // List matching picks from the product search, which lists merged
        // products (families), not bare canonical rows.
        Long brandId = jdbcClient.sql("""
                        INSERT INTO app.brand (normalized_name, display_name)
                        VALUES ('imlek', 'Imlek')
                        ON CONFLICT (normalized_name) DO UPDATE
                        SET display_name = EXCLUDED.display_name
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long familyId = jdbcClient.sql("""
                        INSERT INTO app.product_family (
                            family_key,
                            display_name,
                            normalized_name,
                            brand_id,
                            quantity_value,
                            base_unit
                        )
                        VALUES (
                            'PK046-AUTO-MILK',
                            'PK046 Imlek mleko 1 l',
                            'pk 046 imlek mleko 1 l',
                            ?,
                            1000,
                            'ml'
                        )
                        RETURNING id
                        """)
                .param(1, brandId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO app.product_family_member (
                            family_id,
                            canonical_product_id,
                            relation_type,
                            confidence
                        )
                        VALUES (?, ?, 'SINGLE_GTIN', 1.0000)
                        """)
                .param(1, familyId)
                .param(2, canonicalProductId)
                .update();

        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Matching korpa"),
                clientToken
        );

        shoppingListService.addItem(
                shoppingList.id(),
                clientToken,
                new AddShoppingListItemRequest(
                        "PK046 Imlek mleko 1 l",
                        null,
                        null,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT
                )
        );

        ShoppingListMatchingResponse result =
                shoppingListMatchingService.match(
                        shoppingList.id(),
                        clientToken
                );

        assertThat(result.readyForOptimization()).isTrue();
        assertThat(result.automaticallyMatchedItems()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.matchingStatus())
                    .isEqualTo(
                            ShoppingItemMatchingStatus.AUTO_MATCHED
                    );
            assertThat(item.matchedCanonicalProductId())
                    .isEqualTo(canonicalProductId);
            assertThat(item.candidates()).isNotEmpty();
        });
    }

    @Test
    void validPriceUsesStoreScopeAndDiscountOnlyInsidePeriod() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK049', 'PK049 lanac')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'PILOT', 'Pilot format')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();

        Long storeId = insertVerifiedStore(
                retailerId,
                formatId,
                "PK049-STORE",
                "PK049 prodavnica",
                44.2700,
                19.8840,
                true
        );

        Long canonicalProductId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            barcode,
                            quantity_value,
                            base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, "PK049-BREAD")
                .param(2, "PK049 hleb")
                .param(3, "pk049 hleb")
                .param(4, "8601234567899")
                .param(5, 1)
                .param(6, "piece")
                .query(Long.class)
                .single();

        Long retailerProductId = jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            name,
                            normalized_name,
                            barcode,
                            quantity_value,
                            base_unit,
                            canonical_product_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, "BARCODE:8601234567899")
                .param(3, "PK049 hleb")
                .param(4, "pk049 hleb")
                .param(5, "8601234567899")
                .param(6, 1)
                .param(7, "piece")
                .param(8, canonicalProductId)
                .query(Long.class)
                .single();

        Long importRunId = jdbcClient.sql("""
                        INSERT INTO app.import_run (
                            retailer_id,
                            source_url,
                            status
                        )
                        VALUES (?, 'https://example.test/pk049.csv', 'SUCCEEDED')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO app.price_observation (
                            retailer_product_id,
                            import_run_id,
                            retailer_format_name,
                            store_id,
                            price_date,
                            regular_price,
                            discounted_price,
                            discount_start,
                            discount_end
                        )
                        VALUES
                            (?, ?, NULL, NULL, '2026-08-01', 100, NULL, NULL, NULL),
                            (?, ?, 'Pilot format', NULL, '2026-08-01', 90, NULL, NULL, NULL),
                            (?, ?, NULL, ?, '2026-08-01', 80, 60, '2026-08-01', '2026-08-04')
                        """)
                .param(1, retailerProductId)
                .param(2, importRunId)
                .param(3, retailerProductId)
                .param(4, importRunId)
                .param(5, retailerProductId)
                .param(6, importRunId)
                .param(7, storeId)
                .update();

        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("PK049 korpa"),
                "pk049-owner"
        );

        shoppingListService.addItem(
                shoppingList.id(),
                "pk049-owner",
                new AddShoppingListItemRequest(
                        "PK049 hleb",
                        null,
                        "8601234567899",
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT
                )
        );

        StoreItemOffer activeDiscount = storeShoppingOfferRepository
                .findOffers(
                        shoppingList.id(),
                        List.of(storeId),
                        LocalDate.of(2026, 8, 3)
                ).getFirst();

        StoreItemOffer expiredDiscount = storeShoppingOfferRepository
                .findOffers(
                        shoppingList.id(),
                        List.of(storeId),
                        LocalDate.of(2026, 8, 5)
                ).getFirst();

        assertThat(activeDiscount.priceScope()).isEqualTo("STORE");
        assertThat(activeDiscount.effectivePrice())
                .isEqualByComparingTo("60");
        assertThat(expiredDiscount.priceScope()).isEqualTo("STORE");
        assertThat(expiredDiscount.effectivePrice())
                .isEqualByComparingTo("80");
    }

    @Test
    void flexibleOfferRejectsCheapProductsThatOnlyMentionCategoryLater() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK050', 'PK050 lanac')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long formatId = jdbcClient.sql("""
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

        Long storeId = insertVerifiedStore(
                retailerId,
                formatId,
                "PK050-STORE",
                "PK050 prodavnica",
                44.2700,
                19.8840,
                true
        );

        Long importRunId = jdbcClient.sql("""
                        INSERT INTO app.import_run (
                            retailer_id,
                            source_url,
                            status
                        )
                        VALUES (?, 'https://example.test/pk050.csv', 'SUCCEEDED')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            category_name,
                            name,
                            normalized_name
                        )
                        VALUES
                            (?, 'BAD-WATER', 'Bezalkoholna pića, kafa, čaj',
                             'LOPTA GUMENA VODA VODA VODICA',
                             'lopta gumena voda voda vodica'),
                            (?, 'GOOD-WATER', 'Bezalkoholna pića, kafa, čaj',
                             'VODA MINERALNA 1L', 'voda mineralna 1 l'),
                            (?, 'BAD-WINE', 'Papirna i kuhinjska galanterija',
                             'KESA RUČICA PANE I VINO',
                             'kesa rucica pane i vino'),
                            (?, 'GOOD-WINE', NULL,
                             'VINO CRNO 0.75L', 'vino crno 0 75 l'),
                            (?, 'BAD-BREAD', 'Hleb i peciva',
                             'STAPIĆ SA BELIM LUKOM 45G HLEB&KIFL',
                             'stapic sa belim lukom 45 g hleb kifl'),
                            (?, 'GOOD-BREAD', 'Hleb i peciva',
                             'HLEB BELI 500G', 'hleb beli 500 g')
                        """)
                .params(
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId
                )
                .update();

        jdbcClient.sql("""
                        INSERT INTO app.price_observation (
                            retailer_product_id,
                            import_run_id,
                            price_date,
                            regular_price
                        )
                        SELECT product.id,
                               ?,
                               '2026-08-21',
                               CASE product.source_product_key
                                   WHEN 'BAD-WATER' THEN 1.06
                                   WHEN 'GOOD-WATER' THEN 60
                                   WHEN 'BAD-WINE' THEN 6.85
                                   WHEN 'GOOD-WINE' THEN 250
                                   WHEN 'BAD-BREAD' THEN 23.78
                                   ELSE 80
                               END
                        FROM app.retailer_product AS product
                        WHERE product.retailer_id = ?
                        """)
                .param(1, importRunId)
                .param(2, retailerId)
                .update();

        productCatalogMaintenanceService.refreshRetailer(retailerId);

        String clientToken = "pk050-flexible-prefix";
        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Fleksibilna korpa"),
                clientToken
        );

        for (String category : List.of("voda", "vino", "hleb")) {
            shoppingListService.addItem(
                    shoppingList.id(),
                    clientToken,
                    new AddShoppingListItemRequest(
                            category,
                            category,
                            null,
                            BigDecimal.ONE,
                            ShoppingItemRule.FLEXIBLE_CATEGORY,
                            new FlexibleItemConstraints(
                                    category,
                                    null,
                                    null,
                                    null,
                                    null
                            )
                    )
            );
        }

        List<StoreItemOffer> offers = storeShoppingOfferRepository.findOffers(
                shoppingList.id(),
                List.of(storeId),
                LocalDate.of(2026, 8, 22)
        );

        assertThat(offers)
                .extracting(
                        StoreItemOffer::requestedName,
                        StoreItemOffer::productName
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "voda",
                                "VODA MINERALNA 1L"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "vino",
                                "VINO CRNO 0.75L"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "hleb",
                                "HLEB BELI 500G"
                        )
                );
    }

    @Test
    void flexibleOfferUsesPreciseTypeInsteadOfBroadSourceCategory() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK067', 'PK067 lanac')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long formatId = jdbcClient.sql("""
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

        Long storeId = insertVerifiedStore(
                retailerId,
                formatId,
                "PK067-STORE",
                "PK067 prodavnica",
                44.2700,
                19.8840,
                true
        );

        Long importRunId = jdbcClient.sql("""
                        INSERT INTO app.import_run (
                            retailer_id,
                            source_url,
                            status
                        )
                        VALUES (?, 'https://example.test/pk067.csv', 'SUCCEEDED')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            category_code,
                            category_name,
                            name,
                            normalized_name
                        )
                        VALUES
                            (?, 'BAKING-POWDER', '5', 'Hleb i peciva',
                             'PRAŠAK ZA PECIVO 10G',
                             'prasak za pecivo 10 g'),
                            (?, 'BREAD', '5', 'Hleb i peciva',
                             'HLEB BELI 500G', 'hleb beli 500 g'),
                            (?, 'BAKERY-ROLL', '5', 'Hleb i peciva',
                             'KIFLA BELA 100G', 'kifla bela 100 g'),
                            (?, 'SOUR-MILK', '1', 'Mleko i mlečni proizvodi',
                             'KIS. MLEKO 2,8% 180G',
                             'kis mleko 2,8 % 180 g'),
                            (?, 'MILK', '1', 'Mleko i mlečni proizvodi',
                             'MLEKO STERILIZOVANO 3,2% 1L',
                             'mleko sterilizovano 3,2 % 1 l'),
                            (?, 'BODY-MILK', NULL, NULL,
                             'MLEKO ZA TELO KOKOS 200ML',
                             'mleko za telo kokos 200 ml'),
                            (?, 'EGG-DYE', '1', 'Mleko i mlečni proizvodi',
                             'BOJA ZA JAJA 3G-BORDO',
                             'boja za jaja 3 g bordo'),
                            (?, 'EGGS', '1', 'Mleko i mlečni proizvodi',
                             'JAJA KOKOŠIJA 10/1', 'jaja kokosija 10 1'),
                            (?, 'WINE-BAG', NULL, NULL,
                             'KESA RUČICA PANE I VINO 280X530',
                             'kesa rucica pane i vino 280x530'),
                            (?, 'WINE', NULL, NULL,
                             'VINO CRNO 0.75L', 'vino crno 0,75 l')
                        """)
                .params(
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId,
                        retailerId
                )
                .update();

        jdbcClient.sql("""
                        INSERT INTO app.price_observation (
                            retailer_product_id,
                            import_run_id,
                            price_date,
                            regular_price
                        )
                        SELECT product.id,
                               ?,
                               '2026-09-05',
                               CASE product.source_product_key
                                   WHEN 'BAKING-POWDER' THEN 6.49
                                   WHEN 'BREAD' THEN 79.99
                                   WHEN 'BAKERY-ROLL' THEN 29.99
                                   WHEN 'SOUR-MILK' THEN 28.99
                                   WHEN 'MILK' THEN 119.99
                                   WHEN 'EGG-DYE' THEN 19.99
                                   WHEN 'EGGS' THEN 199.99
                                   WHEN 'WINE-BAG' THEN 6.85
                                   ELSE 499.99
                               END
                        FROM app.retailer_product AS product
                        WHERE product.retailer_id = ?
                        """)
                .param(1, importRunId)
                .param(2, retailerId)
                .update();

        productCatalogMaintenanceService.refreshRetailer(retailerId);

        List<String> classifiedTypes = jdbcClient.sql("""
                        SELECT product.source_product_key || ':' || type.code
                        FROM app.retailer_product AS product
                        JOIN app.retailer_product_type AS assignment
                          ON assignment.retailer_product_id = product.id
                        JOIN app.product_type AS type
                          ON type.id = assignment.product_type_id
                        WHERE product.retailer_id = ?
                        ORDER BY product.source_product_key
                        """)
                .param(1, retailerId)
                .query(String.class)
                .list();

        assertThat(classifiedTypes).containsExactly(
                "BAKERY-ROLL:BAKERY_ROLL",
                "BAKING-POWDER:BAKING_POWDER",
                "BREAD:BREAD",
                "EGGS:EGGS",
                "MILK:MILK",
                "SOUR-MILK:SOUR_MILK",
                "WINE:WINE"
        );

        assertThat(classifiedTypes)
                .noneMatch(value -> value.startsWith("BODY-MILK:"))
                .noneMatch(value -> value.startsWith("EGG-DYE:"))
                .noneMatch(value -> value.startsWith("WINE-BAG:"));

        List<String> extractedAttributes = jdbcClient.sql("""
                        SELECT product.source_product_key || ':' ||
                               definition.code || '=' ||
                               COALESCE(
                                   attribute.numeric_value::TEXT,
                                   attribute.text_value
                               )
                        FROM app.retailer_product_attribute AS attribute
                        JOIN app.retailer_product AS product
                          ON product.id = attribute.retailer_product_id
                        JOIN app.product_attribute_definition AS definition
                          ON definition.id =
                             attribute.attribute_definition_id
                        WHERE product.retailer_id = ?
                          AND definition.code IN (
                              'FAT_PERCENT', 'PROCESSING'
                          )
                        ORDER BY product.source_product_key,
                                 definition.code
                        """)
                .param(1, retailerId)
                .query(String.class)
                .list();

        assertThat(extractedAttributes).containsExactly(
                "MILK:FAT_PERCENT=3.2000",
                "MILK:PROCESSING=UHT",
                "SOUR-MILK:FAT_PERCENT=2.8000"
        );

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.retailer_product_attribute AS attribute
                        JOIN app.retailer_product AS product
                          ON product.id = attribute.retailer_product_id
                        WHERE product.retailer_id = ?
                          AND product.source_product_key = 'BODY-MILK'
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single()).isZero();

        String clientToken = "pk067-precise-types";
        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Precizni tipovi"),
                clientToken
        );

        for (String category : List.of(
                "hleb",
                "pecivo",
                "mleko",
                "jaja",
                "vino"
        )) {
            shoppingListService.addItem(
                    shoppingList.id(),
                    clientToken,
                    new AddShoppingListItemRequest(
                            category,
                            category,
                            null,
                            BigDecimal.ONE,
                            ShoppingItemRule.FLEXIBLE_CATEGORY,
                            new FlexibleItemConstraints(
                                    category,
                                    null,
                                    null,
                                    null,
                                    null
                            )
                    )
            );
        }

        List<StoreItemOffer> offers = storeShoppingOfferRepository.findOffers(
                shoppingList.id(),
                List.of(storeId),
                LocalDate.of(2026, 9, 5)
        );

        assertThat(offers)
                .extracting(
                        StoreItemOffer::requestedName,
                        StoreItemOffer::productName
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "hleb",
                                "HLEB BELI 500G"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "pecivo",
                                "KIFLA BELA 100G"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "mleko",
                                "MLEKO STERILIZOVANO 3,2% 1L"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "jaja",
                                "JAJA KOKOŠIJA 10/1"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "vino",
                                "VINO CRNO 0.75L"
                        )
                );
    }

    @Test
    void priceAwareSearchFiltersBeforePagingAndKeepsExactBarcodeWithoutPrice() {
        long retailer=jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('LIDL','Lidl') RETURNING id").query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO app.retailer_product(retailer_id,source_product_key,name,normalized_name,brand,barcode)
                VALUES (?,'missing','Sveze mleko 1l','sveze mleko 1 l','Pilos','4056489509400'),
                       (?,'priced','Sveze mleko 2.8% 1l','sveze mleko 2 8 1 l','Pilos',NULL),
                       (?,'old','Sveze mleko staro','sveze mleko staro','Pilos',NULL),
                       (?,'future','Sveze mleko buduce','sveze mleko buduce','Pilos',NULL)
                """).params(retailer,retailer,retailer,retailer).update();
        insertCanonicalProduct("EAN:4056489509400", "Sveze mleko 1l", "4056489509400", 1000);
        jdbcClient.sql("""
                UPDATE app.canonical_product SET normalized_name='sveze mleko 1 l',brand='Pilos',base_unit='ml'
                WHERE barcode='4056489509400'
                """).update();
        jdbcClient.sql("""
                UPDATE app.retailer_product SET canonical_product_id=(
                    SELECT id FROM app.canonical_product WHERE barcode='4056489509400')
                WHERE retailer_id=? AND source_product_key='missing'
                """).param(retailer).update();
        productCatalogMaintenanceService.refreshRetailer(retailer);
        jdbcClient.sql("""
                INSERT INTO app.product_retailer_presence(product_family_id,retailer_id,first_seen_date,last_seen_date,
                    latest_price_date,current_offer_count,store_count,format_count,minimum_effective_price)
                SELECT product_family_id,retailer_id,d,d,d,1,0,1,100 FROM app.retailer_product
                CROSS JOIN LATERAL (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Belgrade')::date +
                    CASE source_product_key WHEN 'old' THEN -31 WHEN 'future' THEN 1 ELSE 0 END AS d) dates
                WHERE retailer_id=? AND source_product_key<>'missing'
                """).param(retailer).update();
        var filtered=canonicalProductSearchService.search("Pilos mleko",0,1,false);
        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.items().getFirst().hasUsablePrice()).isTrue();
        assertThat(filtered.items().getFirst().knownRetailers()).containsExactly("Lidl");
        assertThat(filtered.hasNext()).isFalse();
        var all=canonicalProductSearchService.search("Pilos mleko",0,1,true);
        assertThat(all.totalElements()).isEqualTo(4);
        assertThat(all.items().getFirst().hasUsablePrice()).isTrue();
        assertThat(all.hasNext()).isTrue();
        var barcode=canonicalProductSearchService.search("4056489509400",0,10,false);
        assertThat(barcode.items()).hasSize(1);
        assertThat(barcode.items().getFirst().hasUsablePrice()).isFalse();
        assertThat(barcode.items().getFirst().knownRetailers()).containsExactly("Lidl");
    }

    @Test
    void interruptedPriceWriteRollsBackEarlierBatchesAndCatalog() throws Exception {
        assertFailedPromotionPreservesSnapshot(false);
    }

    @Test
    void failedCatalogRefreshRollsBackPricesAndHistory() throws Exception {
        assertFailedPromotionPreservesSnapshot(true);
    }

    private void assertFailedPromotionPreservesSnapshot(boolean failCatalog) throws Exception {
        registerPriceTestRetailer("ATOMIC", "/prices.csv");
        priceImportService.importPrices("ATOMIC");
        String before = importBusinessFingerprint();
        String path = "/atomic-" + java.util.UUID.randomUUID() + ".csv";
        StringBuilder csv = new StringBuilder(NEXT_DAY_CSV_CONTENT);
        // Failure is after a full 500-row batch, not just on the first write.
        for (int i = 0; i < 505; i++) {
            csv.append("MLEKO;Mleko;Atomic test ").append(i)
                    .append(" 1l;Atomic;;l;Test format;").append(i == 504 ? "999" : "170")
                    .append(";;03-03-2026;170;;;20\n");
        }
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        csvServer.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        jdbcClient.sql("UPDATE app.retailer SET dataset_url=? WHERE code='ATOMIC'")
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + path).update();
        String table = failCatalog ? "product_retailer_presence" : "current_price_offer";
        jdbcClient.sql("""
                CREATE FUNCTION app.atomic_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'intentional atomic promotion failure'; END $$
                """).update();
        jdbcClient.sql("CREATE TRIGGER atomic_test_failure BEFORE INSERT OR UPDATE ON app." + table
                + " FOR EACH ROW " + (failCatalog ? "" : "WHEN (NEW.regular_price = 999) ")
                + "EXECUTE FUNCTION app.atomic_test_fail()").update();
        try {
            assertThatThrownBy(() -> priceImportService.importPrices("ATOMIC"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("intentional atomic promotion failure");
            assertThat(importBusinessFingerprint()).isEqualTo(before);
            assertThat(jdbcClient.sql("SELECT status || ':' || rows_saved FROM app.import_run ORDER BY id DESC LIMIT 1")
                    .query(String.class).single()).isEqualTo("FAILED:0");
        } finally {
            jdbcClient.sql("DROP TRIGGER atomic_test_failure ON app." + table).update();
            jdbcClient.sql("DROP FUNCTION app.atomic_test_fail()").update();
            csvServer.removeContext(path);
        }
    }

    private String importBusinessFingerprint() {
        StringBuilder fingerprint = new StringBuilder();
        for (String table : List.of("current_price_offer", "price_observation", "retailer_product",
                "canonical_product", "product_family", "product_family_member", "product_retailer_presence", "brand")) {
            fingerprint.append(jdbcClient.sql("SELECT md5(COALESCE(string_agg(row_data, '' ORDER BY row_data), '')) "
                    + "FROM (SELECT to_jsonb(t)::text row_data FROM app." + table + " t) rows")
                    .query(String.class).single());
        }
        return fingerprint.toString();
    }

    @Test
    void searchSortsSameNameFamiliesWithoutCanonicalRepresentative() {
        jdbcClient.sql("""
                INSERT INTO app.product_family(family_key,display_name,normalized_name)
                VALUES('NO-CANONICAL-A','Regression mleko','regression mleko'),
                      ('NO-CANONICAL-B','Regression mleko','regression mleko')
                """).update();
        var results = canonicalProductSearchService.search("Regression mleko",0,20).items();
        assertThat(results).hasSize(2).allMatch(item -> item.canonicalProductId() == null);
        assertThat(results.getFirst().productFamilyId()).isLessThan(results.getLast().productFamilyId());
    }

    @Test
    void brandSearchFindsProductsWhoseNameOmitsTheBrand() {
        long retailer = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('LIDL','Lidl') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO app.retailer_product(retailer_id,source_product_key,name,normalized_name,brand)
                VALUES (?,'milk','Dugotrajno mleko 1l','dugotrajno mleko 1 l','Pilos'),
                       (?,'yogurt','Jogurt 1kg','jogurt 1 kg','Pilos'),
                       (?,'other','Mleko 1l','mleko 1 l','Drugi brend')
                """).params(retailer,retailer,retailer).update();
        productCatalogMaintenanceService.refreshRetailer(retailer);
        assertThat(canonicalProductSearchService.search("Pilos",0,20).items())
                .hasSize(2).allMatch(item -> "Pilos".equals(item.brand()));
        for (String query : List.of("mleko Pilos", "Pilos mleko", "Пилос млеко")) {
            assertThat(canonicalProductSearchService.search(query,0,20).items())
                    // Shown under its composed name (V84).
                    .anyMatch(item -> "PILOS dugotrajno mleko 1l".equals(item.name()) && "Pilos".equals(item.brand()));
        }
        assertThat(canonicalProductSearchService.search("Pilos nepostojeci",0,20).items()).isEmpty();
    }

    /**
     * "mlkeo" and "helb" used to find nothing at all: two swapped letters
     * leave a short word with almost no trigram in common with the word that
     * was meant, so the query is tried once more against the words a shop
     * actually uses.
     */
    /**
     * Od V95 spisak pripada nalogu, a telefon je samo jedan ulaz u njega.
     * Dok se niko nije prijavio, nalog i dalje ne zna ništa o vlasniku.
     */
    /**
     * Zbog čega prijava uopšte postoji: isti čovek na drugom telefonu zatiče
     * svoje spiskove, a ne praznu aplikaciju. Provera samog Google tokena je
     * posao `GoogleIdentityVerifierTest`-a; ovde se gleda šta se dešava sa
     * spiskovima.
     */
    @Test
    void signingInOnASecondPhoneBringsTheFirstPhonesLists() {
        when(googleVerifier.subjectOf("token-od-google")).thenReturn("isti-covek");

        shoppingListService.create(
                new CreateShoppingListRequest("Stari telefon"), "telefon-1");
        accountSignInService.signInWithGoogle("telefon-1", "token-od-google");

        // Nov telefon: svoj spisak, pa prijava istim nalogom.
        shoppingListService.create(
                new CreateShoppingListRequest("Novi telefon"), "telefon-2");
        assertThat(accountSignInService.state("telefon-2").signedIn()).isFalse();

        accountSignInService.signInWithGoogle("telefon-2", "token-od-google");

        assertThat(accountSignInService.state("telefon-2").signedIn()).isTrue();
        assertThat(shoppingListService.findAll("telefon-2"))
                .extracting(ShoppingListSummary::name)
                .containsExactlyInAnyOrder("Stari telefon", "Novi telefon");
        // Stari telefon gleda u isti nalog, pa vidi isto.
        assertThat(shoppingListService.findAll("telefon-1"))
                .extracting(ShoppingListSummary::name)
                .containsExactlyInAnyOrder("Stari telefon", "Novi telefon");
        // Prazan nalog sa kog je telefon prešao se ne zadržava.
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.account")
                .query(Long.class).single()).isEqualTo(1L);
    }

    /**
     * Domaćinstvo: drugi telefon skenira kod sa prvog i ulazi u isti nalog.
     * Ništa sa njega se ne gubi — ni računi ni kartice, koji su do sada pri
     * prijavi sa drugog telefona tiho nestajali zajedno sa praznim nalogom.
     */
    @Test
    void aHouseholdPhoneJoinsWithEverythingItHad() {
        shoppingListService.create(new CreateShoppingListRequest("Kućni spisak"), "mama");
        shoppingListService.create(new CreateShoppingListRequest("Tatin spisak"), "tata");
        loyaltyCardService.add("tata", "Tatina kartica", "123456789", "CODE_128");
        loyaltyCardService.add("mama", "Ista kartica", "123456789", "CODE_128");
        long tata = jdbcClient.sql("""
                        SELECT account_id FROM app.shopping_list WHERE name = 'Tatin spisak'
                        """).query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO app.receipt(account_id,verification_key,shop_name,issued_at,total_amount)
                VALUES (?,'DOMACINSTVO-1','Pekara','2026-09-01T08:00:00Z',120.00)
                """).param(tata).update();

        var invite = accountSignInService.invite("mama");
        accountSignInService.join("tata", invite.code());

        assertThat(shoppingListService.findAll("tata"))
                .extracting(ShoppingListSummary::name)
                .containsExactlyInAnyOrder("Kućni spisak", "Tatin spisak");
        assertThat(receiptService.history("mama", 50)).singleElement()
                .satisfies(receipt -> assertThat(receipt.shopName()).isEqualTo("Pekara"));
        // Ista kartica u oba telefona ostaje jedna.
        assertThat(loyaltyCardService.cards("mama")).singleElement()
                .satisfies(card -> assertThat(card.name()).isEqualTo("Ista kartica"));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.account")
                .query(Long.class).single()).isEqualTo(1L);

        // Kod radi jednom, a istekao ne radi uopšte.
        assertThatThrownBy(() -> accountSignInService.join("komsija", invite.code()))
                .hasMessageContaining("404");
        var stale = accountSignInService.invite("mama");
        jdbcClient.sql("UPDATE app.account SET invite_expires_at = NOW() - INTERVAL '1 minute'").update();
        assertThatThrownBy(() -> accountSignInService.join("komsija", stale.code()))
                .hasMessageContaining("404");

        // Brisanje u domaćinstvu: tata izlazi, a mamino ostaje netaknuto.
        assertThat(accountSignInService.state("mama").household()).isTrue();
        accountSignInService.delete("tata");
        assertThat(accountSignInService.state("mama").household()).isFalse();
        assertThat(receiptService.history("mama", 50)).hasSize(1);
        // Poslednji na nalogu briše sve.
        accountSignInService.delete("mama");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.account").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.receipt").query(Long.class).single()).isZero();
    }

    /**
     * Pad sa telefona stiže bez broja uređaja; isti pad u istoj verziji se
     * na /admin vidi jednom, sa brojem ponavljanja.
     */
    @Test
    void aCrashFromAPhoneShowsUpOnceWithItsCount() {
        String trace = "java.lang.IllegalStateException: prazan spisak\n\tat rs.App.main(App.kt:1)";
        for (int crash = 0; crash < 2; crash++) {
            crashReportController.report(new rs.pametnakupovina.backend.crash.CrashReportController.CrashReport(
                    "1.4", "16", "Google Pixel 8", trace));
        }
        crashReportController.report(new rs.pametnakupovina.backend.crash.CrashReportController.CrashReport(
                "1.4", "14", "Samsung A52", "java.lang.NullPointerException\n\tat rs.App.other(App.kt:2)"));

        assertThat(crashReportController.crashes())
                .extracting(group -> group.problem() + " x" + group.times())
                .containsExactlyInAnyOrder(
                        "java.lang.IllegalStateException: prazan spisak x2",
                        "java.lang.NullPointerException x1");
        assertThatThrownBy(() -> crashReportController.report(
                new rs.pametnakupovina.backend.crash.CrashReportController.CrashReport("1.4", "16", "x", "  ")))
                .hasMessageContaining("400");
    }

    /**
     * Račun se zavodi iz samog QR koda: prodavnica, vreme i iznos stoje u
     * njemu, pa skeniranje radi i kad u prodavnici nema signala za Poresku
     * upravu. Isti račun skeniran dvaput ostaje jedan.
     */
    @Test
    void aScannedReceiptIsFiledFromItsOwnCodeAndCountsTowardsSpending() {
        String code = "https://suf.purs.gov.rs/v/?vl="
                + "A0xVRURWOExCRHQxT3YxbzA0AQAANAEAAEDr0gEAAAAAAAABg82GSNIAAApNaWxvamtvIDIy";

        var receipt = receiptService.scan("telefon-racun", code);

        assertThat(receipt.invoiceNumber()).isEqualTo("LUEDV8LB-Dt1Ov1o0-308");
        assertThat(receipt.shopName()).isEqualTo("Milojko 22");
        assertThat(receipt.totalAmount())
                .isEqualByComparingTo(new java.math.BigDecimal("3060.00"));
        assertThat(receipt.itemsRead()).isFalse();

        // Skeniran dvaput je i dalje jedan račun.
        assertThat(receiptService.scan("telefon-racun", code).id())
                .isEqualTo(receipt.id());
        assertThat(receiptService.history("telefon-racun", 50)).hasSize(1);

        var spending = receiptService.spending("telefon-racun", null);
        assertThat(spending.byMonth()).singleElement().satisfies(month -> {
            assertThat(month.month()).isEqualTo(java.time.LocalDate.of(2022, 10, 1));
            assertThat(month.spent())
                    .isEqualByComparingTo(new java.math.BigDecimal("3060.00"));
            assertThat(month.receipts()).isEqualTo(1);
        });
        assertThat(spending.byShop()).singleElement().satisfies(shop ->
                assertThat(shop.shopName()).isEqualTo("Milojko 22"));

        // Tuđi telefon ne vidi ništa od toga.
        assertThat(receiptService.history("drugi-telefon", 50)).isEmpty();

        // Navike se čitaju i kad ih još nema: prazno, bez greške. Ovaj poziv
        // je falio, pa je upit sa greškom u grupisanju stigao na server.
        assertThat(receiptService.habits("telefon-racun", 20)).isEmpty();
        assertThat(receiptService.habits("drugi-telefon", 20)).isEmpty();
    }

    /**
     * Dashboard treba nedeljne stubove za izabrani mesec, kao na mani.rs:
     * 1-7, 8-14, ..., 29-kraj. Račun iz drugog meseca i računi drugog naloga
     * ne smeju da uđu u zbir.
     */
    @Test
    void weeklySpendingBucketsTheMonthAndIgnoresEverythingOutsideIt() {
        var list = shoppingListService.create(
                new CreateShoppingListRequest("Nedeljna potrošnja"), "telefon-nedelja");
        long accountId = jdbcClient.sql(
                        "SELECT account_id FROM app.shopping_list WHERE id = ?")
                .param(list.id()).query(Long.class).single();

        jdbcClient.sql("""
                INSERT INTO app.receipt(account_id,verification_key,shop_name,issued_at,total_amount)
                VALUES (?,'NEDELJA-1','Prva','2026-06-05T12:00:00Z',1000.00),
                       (?,'NEDELJA-2','Druga','2026-06-12T12:00:00Z',2000.00),
                       (?,'NEDELJA-3','Treca','2026-06-30T12:00:00Z',500.00),
                       (?,'NEDELJA-4','Van meseca','2026-07-02T12:00:00Z',9999.00)
                """).params(accountId, accountId, accountId, accountId).update();

        long drugiNalog = jdbcClient.sql(
                        "SELECT account_id FROM app.shopping_list WHERE id = ?")
                .param(shoppingListService.create(
                        new CreateShoppingListRequest("Tuđ spisak"), "telefon-druga-nedelja"
                ).id())
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO app.receipt(account_id,verification_key,shop_name,issued_at,total_amount)
                VALUES (?,'NEDELJA-5','Tuđa','2026-06-05T12:00:00Z',7777.00)
                """).param(drugiNalog).update();

        var byWeek = receiptService
                .spending("telefon-nedelja", java.time.LocalDate.of(2026, 6, 1))
                .byWeek();

        assertThat(byWeek).extracting(w -> w.bucket()).containsExactly(0, 1, 4);
        assertThat(byWeek.get(0).spent()).isEqualByComparingTo("1000.00");
        assertThat(byWeek.get(1).spent()).isEqualByComparingTo("2000.00");
        // Poslednja nedelja juna ima samo 2 dana (29-30), ne 7, ali to ovaj
        // upit ne treba da zna — bucket 4 je i dalje samo bucket 4.
        assertThat(byWeek.get(2).spent()).isEqualByComparingTo("500.00");

        // Kategorije: mleko ide pod svoju glavnu kategoriju, kesa bez
        // proizvoda i računi bez stavki u „Ostalo" — zbir ostaje 3500.
        long milk = jdbcClient.sql("""
                INSERT INTO app.product_family(family_key,display_name,normalized_name,product_category_id)
                SELECT 'kategorija-mleko','Mleko 1l','mleko 1l',id
                FROM app.product_category WHERE code = 'MILK'
                RETURNING id
                """).query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO app.receipt_item(receipt_id,line_number,name,quantity,total_price,product_family_id)
                SELECT id,1,'MLEKO 1L',1,900.00,? FROM app.receipt WHERE verification_key = 'NEDELJA-1'
                UNION ALL
                SELECT id,2,'KESA',1,100.00,NULL FROM app.receipt WHERE verification_key = 'NEDELJA-1'
                """).param(milk).update();

        var byCategory = receiptService
                .spending("telefon-nedelja", java.time.LocalDate.of(2026, 6, 1))
                .byCategory();
        assertThat(byCategory).extracting(c -> c.category())
                .containsExactly("Ostalo", "Mlečni proizvodi i jaja");
        assertThat(byCategory.get(0).spent()).isEqualByComparingTo("2600.00");
        assertThat(byCategory.get(1).spent()).isEqualByComparingTo("900.00");

        // Prazan mesec ne puca, samo je prazan.
        var january = receiptService
                .spending("telefon-nedelja", java.time.LocalDate.of(2026, 1, 1));
        assertThat(january.byWeek()).isEmpty();
        assertThat(january.byCategory()).isEmpty();
    }

    /**
     * Gomila plastike ostaje kod kuće, ali broj sa kartice mora da izađe pred
     * kasirku tačno onakav kakav je odštampan.
     */
    @Test
    void aLoyaltyCardIsKeptForTheTillAndNeverShownToAnotherPhone() {
        var card = loyaltyCardService.add(
                "telefon-kartica", "Super Kartica", "6108560008584550", "EAN_13");

        assertThat(card.cardNumber()).isEqualTo("6108560008584550");
        assertThat(card.barcodeFormat()).isEqualTo("EAN_13");

        // Ista kartica dodata dvaput je i dalje jedna, samo osveženog naziva.
        var again = loyaltyCardService.add(
                "telefon-kartica", "Super kartica (žena)", "6108560008584550", "EAN_13");
        assertThat(again.id()).isEqualTo(card.id());
        assertThat(loyaltyCardService.cards("telefon-kartica"))
                .singleElement()
                .satisfies(only ->
                        assertThat(only.name()).isEqualTo("Super kartica (žena)"));

        assertThat(loyaltyCardService.cards("drugi-telefon")).isEmpty();
        assertThatThrownBy(() ->
                loyaltyCardService.remove("drugi-telefon", card.id()))
                .hasMessageContaining("404");

        // Prazan broj i oblik koda koji nijedna kasa ne čita se odbijaju.
        assertThatThrownBy(() ->
                loyaltyCardService.add("telefon-kartica", "Prazna", "   ", "EAN_13"))
                .hasMessageContaining("ne sme biti prazan");
        assertThatThrownBy(() ->
                loyaltyCardService.add("telefon-kartica", "Čudna", "123456", "MAGIJA"))
                .hasMessageContaining("Nepoznat oblik");

        loyaltyCardService.remove("telefon-kartica", card.id());
        assertThat(loyaltyCardService.cards("telefon-kartica")).isEmpty();
    }

    @Test
    void aPhoneKeepsOneAccountAndTwoPhonesNeverShareLists() {
        var first = shoppingListService.create(
                new CreateShoppingListRequest("Prvi spisak"), "uredjaj-a");
        shoppingListService.create(
                new CreateShoppingListRequest("Drugi spisak"), "uredjaj-b");

        assertThat(shoppingListService.findAll("uredjaj-a"))
                .extracting(ShoppingListSummary::name)
                .containsExactly("Prvi spisak");
        assertThat(shoppingListService.findAll("uredjaj-b"))
                .extracting(ShoppingListSummary::name)
                .containsExactly("Drugi spisak");
        // Tuđi spisak ne postoji za ovaj telefon — ni da ga vidi, ni da sazna
        // da postoji.
        assertThatThrownBy(() -> shoppingListService.findById(first.id(), "uredjaj-b"))
                .hasMessageContaining("404");

        // Isti telefon ostaje na istom nalogu koliko god puta se javio.
        shoppingListService.create(
                new CreateShoppingListRequest("Treći spisak"), "uredjaj-a");
        assertThat(jdbcClient.sql(
                        "SELECT COUNT(*) FROM app.account_device").query(Long.class).single())
                .isEqualTo(2L);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(DISTINCT account_id) FROM app.shopping_list
                        """).query(Long.class).single())
                .isEqualTo(2L);
        // Prijave još nema, pa ni jednog ličnog podatka uz nalog.
        assertThat(jdbcClient.sql(
                        "SELECT COUNT(*) FROM app.account_identity").query(Long.class).single())
                .isZero();
    }

    @Test
    void aMistypedQueryIsTriedAgainstTheWordsAShopUses() {
        long retailer = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('TYPO','Typo test') RETURNING id")
                .query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO app.retailer_product(retailer_id,source_product_key,name,normalized_name,brand)
                VALUES (?,'milk','Mleko 1l','mleko 1 l','Pilos'),
                       (?,'bread','Hleb beli 500g','hleb beli 500 g','Klas')
                """).params(retailer,retailer).update();
        productCatalogMaintenanceService.refreshRetailer(retailer);

        var milk = canonicalProductSearchService.search("mlkeo",0,20,true);
        assertThat(milk.query()).isEqualTo("mlkeo");
        assertThat(milk.correctedQuery()).isEqualTo("mleko");
        assertThat(milk.items()).isNotEmpty();
        assertThat(canonicalProductSearchService.search("helb",0,20,true).correctedQuery())
                .isEqualTo("hleb");
        // A longer query is already close enough for the text search to
        // carry the mistyped word, and is left exactly as it was typed.
        var typedBread = canonicalProductSearchService.search("beli helb",0,20,true);
        assertThat(typedBread.correctedQuery()).isNull();
        assertThat(typedBread.items()).isNotEmpty();
        // A query that finds something is searched exactly as it was typed.
        assertThat(canonicalProductSearchService.search("mleko",0,20,true).correctedQuery()).isNull();
        // A word no shop word is one slip from stays a miss, and says so.
        var nonsense = canonicalProductSearchService.search("xyzwq",0,20,true);
        assertThat(nonsense.correctedQuery()).isNull();
        assertThat(nonsense.items()).isEmpty();
    }

    /**
     * Navika sme da odlučuje tek kad je cena ista. Plan ostaje najjeftiniji —
     * ono što kupac obično kupuje ne sme da ga košta ni dinar, jer je cela
     * aplikacija zbog toga i napravljena.
     */
    @Test
    void whatTheyUsuallyBuyBreaksATieAndNeverCostsMore() {
        long retailer = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('NAVIKA','Navika test') RETURNING id")
                .query(Long.class).single();
        long format = jdbcClient.sql("INSERT INTO app.store_format(retailer_id,code,name) VALUES(?,'TEST','Test') RETURNING id")
                .param(retailer).query(Long.class).single();
        long store = insertVerifiedStore(retailer, format, "NAVIKA-SHOP", "Test", 44.27, 19.88, true);
        long run = jdbcClient.sql("INSERT INTO app.import_run(retailer_id,source_url,status) VALUES(?,'https://example.test/navika','SUCCEEDED') RETURNING id")
                .param(retailer).query(Long.class).single();
        var normalizer = new rs.pametnakupovina.backend.matching.ProductNameNormalizer();

        // Dva mleka iste cene i istog pakovanja: ništa osim navike ih ne deli.
        for (String name : List.of("ALFA mleko 2,8%mm 1l", "BETA mleko 2,8%mm 1l")) {
            long product = jdbcClient.sql("""
                    INSERT INTO app.retailer_product(retailer_id,source_product_key,name,normalized_name)
                    VALUES(?,?,?,?) RETURNING id
                    """).params(retailer, name, name, normalizer.normalize(name))
                    .query(Long.class).single();
            jdbcClient.sql("INSERT INTO app.price_observation(retailer_product_id,import_run_id,price_date,regular_price) VALUES(?,?,'2026-09-10',99.99)")
                    .params(product, run).update();
        }
        productCatalogMaintenanceService.refreshRetailer(retailer);

        var list = shoppingListService.create(
                new CreateShoppingListRequest("Navika"), "telefon-navika");
        shoppingListService.addItem(list.id(), "telefon-navika",
                new AddShoppingListItemRequest("mleko", "mleko", null, BigDecimal.ONE,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints("mleko", null, null, null, null)));

        // Bez ijednog računa odlučuje ono što je i do sada: redosled je
        // nepromenjen.
        String withoutReceipts = storeShoppingOfferRepository
                .findOffers(list.id(), List.of(store), LocalDate.of(2026, 9, 10))
                .getFirst().productName();
        assertThat(withoutReceipts).isNotNull();

        // Kupac je BETA mleko već kupovao.
        long betaFamily = jdbcClient.sql("""
                SELECT product_family_id FROM app.retailer_product
                WHERE retailer_id = ? AND name = 'BETA mleko 2,8%mm 1l'
                """).param(retailer).query(Long.class).single();
        long accountId = jdbcClient.sql(
                        "SELECT account_id FROM app.shopping_list WHERE id = ?")
                .param(list.id()).query(Long.class).single();
        long receipt = jdbcClient.sql("""
                INSERT INTO app.receipt(account_id,verification_key,shop_name,issued_at,total_amount)
                VALUES(?,'NAVIKA-1','Prodavnica','2026-09-09T10:00:00Z',99.99) RETURNING id
                """).param(accountId).query(Long.class).single();
        jdbcClient.sql("""
                INSERT INTO app.receipt_item(receipt_id,line_number,name,quantity,total_price,product_family_id)
                VALUES(?,1,'BETA mleko',1,99.99,?)
                """).params(receipt, betaFamily).update();

        var chosen = storeShoppingOfferRepository
                .findOffers(list.id(), List.of(store), LocalDate.of(2026, 9, 10))
                .getFirst();

        assertThat(chosen.productName()).isEqualTo("BETA mleko 2,8%mm 1l");

        // Isto to, ispisano kao „šta obično kupuješ".
        assertThat(receiptService.habits("telefon-navika", 20))
                .singleElement()
                .satisfies(habit -> {
                    assertThat(habit.productFamilyId()).isEqualTo(betaFamily);
                    assertThat(habit.name()).containsIgnoringCase("beta");
                    assertThat(habit.times()).isEqualTo(1);
                });

        // A kad njegovo mleko poskupi, plan ga napušta bez oklevanja.
        jdbcClient.sql("""
                UPDATE app.price_observation SET regular_price = 149.99
                WHERE retailer_product_id IN (
                    SELECT id FROM app.retailer_product
                    WHERE retailer_id = ? AND name = 'BETA mleko 2,8%mm 1l'
                )
                """).param(retailer).update();
        productCatalogMaintenanceService.refreshRetailer(retailer);

        assertThat(storeShoppingOfferRepository
                .findOffers(list.id(), List.of(store), LocalDate.of(2026, 9, 10))
                .getFirst().productName())
                .isEqualTo("ALFA mleko 2,8%mm 1l");
    }

    /**
     * V99: kad brend, veličina, pakovanje i vrsta potpuno poklope i nijedan
     * lanac ne prodaje oba imena, par se više ne pita — spaja se sam, isto
     * kao da je vlasnik kliknuo „Isti proizvod". Prava druga varijanta istog
     * brenda i veličine (druga vrsta paštete) i dalje ostaje poseban proizvod.
     */
    @Test
    void productsThatPassEveryMergeCheckJoinWithoutAskingButATrueVariantStaysApart() {
        var normalizer = new rs.pametnakupovina.backend.matching.ProductNameNormalizer();

        long retailerA = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('MERGEA','Merge A test') RETURNING id")
                .query(Long.class).single();
        long formatA = jdbcClient.sql("INSERT INTO app.store_format(retailer_id,code,name) VALUES(?,'TEST','Test') RETURNING id")
                .param(retailerA).query(Long.class).single();
        insertVerifiedStore(retailerA, formatA, "MERGEA-SHOP", "Test", 44.27, 19.88, true);
        long runA = jdbcClient.sql("INSERT INTO app.import_run(retailer_id,source_url,status) VALUES(?,'https://example.test/mergea','SUCCEEDED') RETURNING id")
                .param(retailerA).query(Long.class).single();

        long retailerB = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('MERGEB','Merge B test') RETURNING id")
                .query(Long.class).single();
        long formatB = jdbcClient.sql("INSERT INTO app.store_format(retailer_id,code,name) VALUES(?,'TEST','Test') RETURNING id")
                .param(retailerB).query(Long.class).single();
        insertVerifiedStore(retailerB, formatB, "MERGEB-SHOP", "Test", 44.27, 19.88, true);
        long runB = jdbcClient.sql("INSERT INTO app.import_run(retailer_id,source_url,status) VALUES(?,'https://example.test/mergeb','SUCCEEDED') RETURNING id")
                .param(retailerB).query(Long.class).single();

        // Isti proizvod, dva različita načina da se ispiše ime — Argeta
        // pikant pašteta 45g, baš kao na admin stranici.
        long sameA = insertMergeCandidate(retailerA, "Pasteta pikant Argeta 45g", "Argeta", 45, "g", normalizer);
        offerCurrentPrice(sameA, runA, 64.99);
        long sameB = insertMergeCandidate(retailerB, "PASTETA ARGETA PIKANT PASTETA 45G", "Argeta", 45, "g", normalizer);
        offerCurrentPrice(sameB, runB, 71.99);

        // Prava druga varijanta istog brenda i veličine: ćureća, ne pikant.
        long variantA = insertMergeCandidate(retailerA, "Pasteta Argeta cureca 45g", "Argeta", 45, "g", normalizer);
        offerCurrentPrice(variantA, runA, 64.99);
        long variantB = insertMergeCandidate(retailerB, "PASTETA ARGETA CURECA 45G", "Argeta", 45, "g", normalizer);
        offerCurrentPrice(variantB, runB, 71.99);

        productCatalogMaintenanceService.refreshRetailer(retailerA);
        productCatalogMaintenanceService.refreshRetailer(retailerB);
        // Odluka koju je drugi refresh upisao se spaja tek na sledećem — isto
        // kao i vlasnikova, "pri sledećem osvežavanju kataloga". Ne zna se
        // unapred čiji ključ pobeđuje, pa oba lanca prolaze još jednom, kao
        // u pravom dnevnom ciklusu koji ionako osvežava sve redom.
        productCatalogMaintenanceService.refreshRetailer(retailerA);
        productCatalogMaintenanceService.refreshRetailer(retailerB);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.product_merge_suggestion")
                .query(Integer.class).single())
                .isZero();

        assertThat(familyOf(sameA)).isEqualTo(familyOf(sameB));
        assertThat(familyOf(variantA)).isEqualTo(familyOf(variantB));
        assertThat(familyOf(variantA)).isNotEqualTo(familyOf(sameA));
    }

    private long insertMergeCandidate(
            long retailerId, String name, String brand, int quantity, String unit,
            rs.pametnakupovina.backend.matching.ProductNameNormalizer normalizer
    ) {
        return jdbcClient.sql("""
                        INSERT INTO app.retailer_product(
                            retailer_id, source_product_key, name, normalized_name,
                            brand, quantity_value, base_unit
                        )
                        VALUES (?,?,?,?,?,?,?) RETURNING id
                        """)
                .params(retailerId, name, name, normalizer.normalize(name), brand, quantity, unit)
                .query(Long.class).single();
    }

    /**
     * product_retailer_presence (pa i merge_family iza njega) čita samo
     * odavde, ne iz price_observation — bez ovog reda porodica je nevidljiva
     * za spajanje koliko god cena postojala.
     */
    private void offerCurrentPrice(long retailerProductId, long importRunId, double price) {
        jdbcClient.sql("""
                        INSERT INTO app.current_price_offer (
                            retailer_product_id, import_run_id, scope_type,
                            retailer_format_name, price_date, first_seen_date,
                            last_seen_date, regular_price
                        )
                        VALUES (?, ?, 'STORE_FORMAT', 'Test', '2026-09-10', '2026-09-10', '2026-09-10', ?)
                        """)
                .params(retailerProductId, importRunId, price)
                .update();
    }

    private Long familyOf(long retailerProductId) {
        return jdbcClient.sql("SELECT product_family_id FROM app.retailer_product WHERE id = ?")
                .param(retailerProductId)
                .query(Long.class)
                .single();
    }

    @Test
    void beerIntentsNeverSubstituteNonAlcoholicForRegularOrTheReverse() {
        long retailer = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('BEERTEST','Beer test') RETURNING id")
                .query(Long.class).single();
        long format = jdbcClient.sql("INSERT INTO app.store_format(retailer_id,code,name) VALUES(?,'TEST','Test') RETURNING id")
                .param(retailer).query(Long.class).single();
        long store = insertVerifiedStore(retailer,format,"BEER-SHOP","Test",44.27,19.88,true);
        long run = jdbcClient.sql("INSERT INTO app.import_run(retailer_id,source_url,status) VALUES(?,'https://example.test/beer','SUCCEEDED') RETURNING id")
                .param(retailer).query(Long.class).single();
        var normalizer = new rs.pametnakupovina.backend.matching.ProductNameNormalizer();
        for (String name : List.of("Pivo lager 0,5l", "Pivo bezalkoholno Bertold 0,5l", "Pivo 0.0 0,5l",
                "Безалкохолно пиво 0,5л", "Pivo bez alkohola 0,5l", "Beer alcohol free 0.5l")) {
            long product = jdbcClient.sql("""
                    INSERT INTO app.retailer_product(retailer_id,source_product_key,name,normalized_name)
                    VALUES(?,?,?,?) RETURNING id
                    """).params(retailer,name,name,normalizer.normalize(name)).query(Long.class).single();
            jdbcClient.sql("INSERT INTO app.price_observation(retailer_product_id,import_run_id,price_date,regular_price) VALUES(?,?,'2026-09-10',?)")
                    .params(product,run,name.contains("lager") ? 100 : 10).update();
        }
        productCatalogMaintenanceService.refreshRetailer(retailer);
        assertThat(jdbcClient.sql("""
                SELECT count(*) FROM app.retailer_product_type a JOIN app.product_type t ON t.id=a.product_type_id
                JOIN app.retailer_product p ON p.id=a.retailer_product_id
                WHERE p.retailer_id=? AND t.code='NON_ALCOHOLIC_BEER'
                """).param(retailer).query(Integer.class).single()).isEqualTo(5);
        var list = shoppingListService.create(new CreateShoppingListRequest("Beer regression"),"beer-regression");
        for (String request : List.of("pivo", "bezalkoholno pivo", "pivo 0.0")) {
            shoppingListService.addItem(list.id(),"beer-regression",new AddShoppingListItemRequest(
                    request,request,null,BigDecimal.ONE,ShoppingItemRule.FLEXIBLE_CATEGORY,
                    new FlexibleItemConstraints(request,null,null,null,null)));
        }
        var offers = storeShoppingOfferRepository.findOffers(list.id(),List.of(store),LocalDate.of(2026,9,10));
        assertThat(offers).hasSize(3);
        assertThat(offers.getFirst().productName()).isEqualTo("Pivo lager 0,5l");
        assertThat(offers.subList(1,3)).allMatch(o -> o.available() && !o.productName().contains("lager"));
        jdbcClient.sql("DELETE FROM app.price_observation WHERE retailer_product_id IN (SELECT id FROM app.retailer_product WHERE retailer_id=? AND name='Pivo lager 0,5l')")
                .param(retailer).update();
        assertThat(storeShoppingOfferRepository.findOffers(list.id(),List.of(store),LocalDate.of(2026,9,10)).getFirst().available()).isFalse();
    }

    @Test
    void amountOffersCompareWholePackagesAndRejectWrongDairyAndExcess() {
        Long retailer = jdbcClient.sql("INSERT INTO app.retailer(code,name) VALUES('M2','M2 test') RETURNING id")
                .query(Long.class).single();
        Long format = jdbcClient.sql("INSERT INTO app.store_format(retailer_id,code,name) VALUES(?,'TEST','Test') RETURNING id")
                .param(retailer).query(Long.class).single();
        Long store = insertVerifiedStore(retailer, format, "M2-SHOP", "Test shop", 44.27, 19.88, true);
        Long run = jdbcClient.sql("INSERT INTO app.import_run(retailer_id,source_url,status) VALUES(?,'https://example.test/m2','SUCCEEDED') RETURNING id")
                .param(retailer).query(Long.class).single();
        var normalizer = new rs.pametnakupovina.backend.matching.ProductNameNormalizer();
        var parser = new rs.pametnakupovina.backend.matching.ProductQuantityParser();
        String[][] products = {
                {"small","JOGURT 2.8% 180G+20G MEGGLE-924","23"},
                {"big","Jogurt 1kg","109"},
                {"excess","Jogurt 2kg","50"},
                {"fruit","Jogurt jagoda 1kg","1"},
                {"banana","Jogurt banana 1kg","0.50"},
                {"mixed-fruit","Vocni jogurt jagoda,tresnja 1kg","0.25"},
                {"kefir","Kefir 1kg","1"},
                {"ayran","Ajran 1kg","1"},
                {"unknown","Jogurt domaći","1"},
                {"choco","MLEKO COKO 0.2L PET IMLEK","10"},
                {"choco-short","MLEKO COK. KRAVICA 250ML","11"},
                {"vanilla","Mleko vanila 0.2l","1"},
                {"cheese","BISER TOPLJ.SIR SUNKA 140g MLEKO","1"},
                {"milk","Mleko 1l","100"},
                {"wafer","NAPOL.FINA MLEKO COKOL. 400G-749","1"}
        };
        for (String[] p : products) {
            var size = parser.parse(p[1]).orElse(null);
            Long id = jdbcClient.sql("""
                    INSERT INTO app.retailer_product(retailer_id,source_product_key,name,normalized_name,quantity_value,base_unit)
                    VALUES(?,?,?,?,?,?) RETURNING id
                    """).params(retailer,p[0],p[1],normalizer.normalize(p[1]))
                    .param(5,size==null?null:size.value(),java.sql.Types.NUMERIC)
                    .param(6,size==null?null:size.unit().databaseValue(),java.sql.Types.VARCHAR)
                    .query(Long.class).single();
            jdbcClient.sql("""
                    INSERT INTO app.price_observation(retailer_product_id,import_run_id,price_date,regular_price)
                    VALUES(?,?,'2026-09-06',?)
                    """).params(id,run,new BigDecimal(p[2])).update();
        }
        productCatalogMaintenanceService.refreshRetailer(retailer);
        assertThat(jdbcClient.sql("""
                SELECT p.source_product_key || ':' || t.code FROM app.retailer_product p
                JOIN app.retailer_product_type a ON a.retailer_product_id=p.id
                JOIN app.product_type t ON t.id=a.product_type_id WHERE p.retailer_id=?
                """).param(retailer).query(String.class).list())
                .contains("fruit:FRUIT_YOGURT", "kefir:KEFIR", "ayran:AYRAN", "choco:FLAVORED_MILK", "milk:MILK",
                        "choco-short:FLAVORED_MILK", "vanilla:FLAVORED_MILK")
                .noneMatch(s -> s.startsWith("wafer:") || s.equals("cheese:MILK"));
        var list = shoppingListService.create(new CreateShoppingListRequest("M2 amounts"),"m2-amounts");
        assertThatThrownBy(() -> shoppingListService.addItem(list.id(),"m2-amounts",new AddShoppingListItemRequest(
                "jogurt nepoznati ukus","jogurt nepoznati ukus",null,BigDecimal.ONE,ShoppingItemRule.FLEXIBLE_CATEGORY,
                new FlexibleItemConstraints("jogurt nepoznati ukus",null,null,null,null))))
                .isInstanceOf(ResponseStatusException.class);
        var item = shoppingListService.addItem(list.id(),"m2-amounts",new AddShoppingListItemRequest(
                "jogurt","jogurt 1kg",null,BigDecimal.ONE,ShoppingItemRule.FLEXIBLE_CATEGORY,
                new FlexibleItemConstraints("jogurt",null,null,null,"g",new BigDecimal("1000"))));
        assertThat(item.flexibleConstraints().targetQuantity()).isEqualByComparingTo("1000");
        var best = storeShoppingOfferRepository.findOffers(list.id(),List.of(store),LocalDate.of(2026,9,6)).getFirst();
        assertThat(best.productName()).isEqualTo("Jogurt 1kg");
        assertThat(best.lineTotal()).isEqualByComparingTo("109");
        assertThat(best.purchaseQuantity().packages()).isEqualByComparingTo("1");
        assertThat(best.purchaseQuantity().unitPrice()).isEqualByComparingTo("109");
        jdbcClient.sql("""
                UPDATE app.price_observation SET regular_price=120 WHERE retailer_product_id IN
                (SELECT id FROM app.retailer_product WHERE retailer_id=? AND source_product_key='big')
                """).param(retailer).update();
        best = storeShoppingOfferRepository.findOffers(list.id(),List.of(store),LocalDate.of(2026,9,6)).getFirst();
        assertThat(best.productName()).startsWith("JOGURT 2.8%");
        assertThat(best.purchaseQuantity().packages()).isEqualByComparingTo("5");
        assertThat(best.purchaseQuantity().suppliedAmount()).isEqualByComparingTo("1000");
        assertThat(best.lineTotal()).isEqualByComparingTo("115");
        jdbcClient.sql("UPDATE app.shopping_list_item SET target_quantity=1100 WHERE id=?").param(item.id()).update();
        best = storeShoppingOfferRepository.findOffers(list.id(),List.of(store),LocalDate.of(2026,9,6)).getFirst();
        assertThat(best.purchaseQuantity().packages()).isEqualByComparingTo("6");
        assertThat(best.purchaseQuantity().extraAmount()).isEqualByComparingTo("100");
        assertThat(best.lineTotal()).isEqualByComparingTo("138");
        // Never compare a litre with a kilogram by assuming density.
        jdbcClient.sql("UPDATE app.shopping_list_item SET required_base_unit='ml' WHERE id=?").param(item.id()).update();
        assertThat(storeShoppingOfferRepository.findOffers(list.id(),List.of(store),LocalDate.of(2026,9,6)).getFirst().available()).isFalse();

        var flavors = shoppingListService.create(new CreateShoppingListRequest("M2 flavors"),"m2-flavors");
        for (String name : List.of("jogurt jagoda", "mleko čokoladno", "kefir", "mleko", "jogurt jagoda 1 kg")) {
            var added = shoppingListService.addItem(flavors.id(), "m2-flavors", new AddShoppingListItemRequest(
                    name,name,null,BigDecimal.ONE,ShoppingItemRule.FLEXIBLE_CATEGORY,
                    new FlexibleItemConstraints(name,null,null,null,null)));
            assertThat(added.matchingStatus()).isEqualTo(ShoppingItemMatchingStatus.CONFIRMED);
        }
        var flavorOffers = storeShoppingOfferRepository.findOffers(flavors.id(),List.of(store),LocalDate.of(2026,9,6));
        assertThat(flavorOffers).extracting(StoreItemOffer::productName)
                .containsExactly("Jogurt jagoda 1kg", "MLEKO COKO 0.2L PET IMLEK", "Kefir 1kg", "Mleko 1l", "Jogurt jagoda 1kg");
        // If strawberry disappears, do not substitute a cheaper banana or plain yogurt.
        jdbcClient.sql("DELETE FROM app.price_observation WHERE retailer_product_id IN " +
                "(SELECT id FROM app.retailer_product WHERE retailer_id=? AND source_product_key='fruit')")
                .param(retailer).update();
        assertThat(storeShoppingOfferRepository.findOffers(flavors.id(),List.of(store),LocalDate.of(2026,9,6))
                .stream().filter(o -> o.requestedName().startsWith("jogurt jagoda")))
                .allMatch(o -> !o.available());
    }

    @Test
    void uncertainProductTypeRequiresReviewBeforeItCanBeUsed() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('PK068', 'PK068 lanac')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        Long productId = jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            name,
                            normalized_name
                        )
                        VALUES (?, 'BANANA', 'BANANA RINFUZ', 'banana rinfuza')
                        RETURNING id
                        """)
                .param(1, retailerId)
                .query(Long.class)
                .single();

        productCatalogMaintenanceService.refreshRetailer(retailerId);

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.retailer_product_type
                        WHERE retailer_product_id = ?
                        """)
                .param(1, productId)
                .query(Long.class)
                .single()).isZero();

        List<ProductTypeCandidateReview> candidates =
                dataQualityService.reviewProductTypes(10);

        assertThat(candidates)
                .extracting(
                        ProductTypeCandidateReview::retailerProductId,
                        ProductTypeCandidateReview::suggestedProductTypeCode
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                productId,
                                "FRUIT"
                        )
                );

        ProductTypeReviewResult result =
                dataQualityService.reviewProductType(
                        productId,
                        new ProductTypeReviewRequest(
                                "ACCEPT",
                                "FRUIT"
                        )
                );

        assertThat(result.reviewed()).isTrue();
        assertThat(result.action()).isEqualTo("ACCEPT");

        assertThat(jdbcClient.sql("""
                        SELECT type.code
                        FROM app.retailer_product_type AS assignment
                        JOIN app.product_type AS type
                          ON type.id = assignment.product_type_id
                        WHERE assignment.retailer_product_id = ?
                          AND assignment.reviewed = TRUE
                        """)
                .param(1, productId)
                .query(String.class)
                .single()).isEqualTo("FRUIT");
    }

    @Test
    void verifiedPriceFormatMappingSelectsCorrectStorePrice() {
        Long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('IDEA_RODA', 'IDEA / Roda')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        Long locationFormatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (
                            retailer_id,
                            code,
                            name
                        )
                        VALUES (?, 'IDEA_LOCATION', 'IDEA (lokacija)')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        Long ideaStoreId = insertVerifiedStore(
                retailerId,
                locationFormatId,
                "IDEA_VALJEVO",
                "IDEA Valjevo",
                44.2702,
                19.8867,
                true
        );
        Long rodaStoreId = insertVerifiedStore(
                retailerId,
                locationFormatId,
                "RODA_407",
                "Roda Valjevo",
                44.2740,
                19.8800,
                true
        );
        Long unlinkedStoreId = insertVerifiedStore(
                retailerId,
                locationFormatId,
                "IDEA_UNKNOWN",
                "IDEA bez MP šifre",
                44.2800,
                19.8900,
                true
        );

        jdbcClient.sql("""
                    INSERT INTO app.store_price_format_mapping (
                        retailer_id,
                        source_store_code,
                        store_external_code,
                        retailer_format_name,
                        verification_status,
                        mapping_method,
                        source_url
                    ) VALUES (
                        ?,
                        'MP405',
                        'IDEA_VALJEVO',
                        'IDEA MARKETI_Cenovnik I0',
                        'VERIFIED',
                        'MANUAL_OFFICIAL_CROSS_REFERENCE',
                        'https://data.gov.rs/test/old.xlsx'
                    )
                    """)
                .param(retailerId)
                .update();

        var mappingResult = storePriceFormatMappingRepository
                .replaceIdeaRodaMappings(new StorePriceFormatSnapshot(
                        "https://data.gov.rs/test/objekti.xlsx",
                        Instant.parse("2026-09-01T05:00:00Z"),
                        List.of(
                                new OfficialStorePriceFormat(
                                        "MP405",
                                        "VALJEVO 1",
                                        "Iplus"
                                ),
                                new OfficialStorePriceFormat(
                                        "MP407",
                                        "RODA VALJEVO",
                                        "Rplus"
                                ),
                                new OfficialStorePriceFormat(
                                        "MP999",
                                        "IDEA BEZ VEZE",
                                        "I0"
                                )
                        )
                ));

        Long canonicalProductId = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (
                            canonical_key,
                            name,
                            normalized_name,
                            barcode
                        )
                        VALUES (
                            'IDEA-RODA-MAPPED-PRODUCT',
                            'Mapirani proizvod',
                            'mapirani proizvod',
                            '3838600041300'
                        )
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        Long retailerProductId = jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id,
                            source_product_key,
                            name,
                            normalized_name,
                            barcode,
                            canonical_product_id
                        )
                        VALUES (
                            ?,
                            'BARCODE:3838600041300',
                            'Mapirani proizvod',
                            'mapirani proizvod',
                            '3838600041300',
                            ?
                        )
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, canonicalProductId)
                .query(Long.class)
                .single();
        Long importRunId = jdbcClient.sql("""
                        INSERT INTO app.import_run (
                            retailer_id,
                            source_url,
                            status
                        )
                        VALUES (
                            ?,
                            'https://data.gov.rs/test/cene.csv',
                            'SUCCEEDED'
                        )
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                    INSERT INTO app.current_price_offer (
                        retailer_product_id,
                        import_run_id,
                        scope_type,
                        retailer_format_name,
                        price_date,
                        first_seen_date,
                        last_seen_date,
                        regular_price
                    ) VALUES
                        (?, ?, 'STORE_FORMAT',
                         'IDEA MARKETI_Cenovnik Iplus',
                         '2026-09-01', '2026-09-01', '2026-09-01', 120),
                        (?, ?, 'STORE_FORMAT',
                         'IDEA MARKETI_Cenovnik Rplus',
                         '2026-09-01', '2026-09-01', '2026-09-01', 90),
                        (?, ?, 'STORE_FORMAT',
                         'IDEA MARKETI_Cenovnik I0',
                         '2026-09-01', '2026-09-01', '2026-09-01', 10)
                    """)
                .params(
                        retailerProductId,
                        importRunId,
                        retailerProductId,
                        importRunId,
                        retailerProductId,
                        importRunId
                )
                .update();

        int eligibleStores = storePriceFormatMappingRepository
                .refreshEligibility(retailerId);
        ShoppingListSummary shoppingList = shoppingListService.create(
                new CreateShoppingListRequest("Mapirana korpa"),
                "mapped-price-owner"
        );
        shoppingListService.addItem(
                shoppingList.id(),
                "mapped-price-owner",
                new AddShoppingListItemRequest(
                        "Mapirani proizvod",
                        null,
                        "3838600041300",
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT
                )
        );

        StoreItemOffer ideaOffer = storeShoppingOfferRepository.findOffers(
                shoppingList.id(),
                List.of(ideaStoreId),
                LocalDate.of(2026, 9, 1)
        ).getFirst();
        StoreItemOffer rodaOffer = storeShoppingOfferRepository.findOffers(
                shoppingList.id(),
                List.of(rodaStoreId),
                LocalDate.of(2026, 9, 1)
        ).getFirst();
        String unlinkedStatus = jdbcClient.sql("""
                        SELECT pricing_eligible || ':' ||
                               pricing_ineligibility_reason
                        FROM app.store
                        WHERE id = ?
                        """)
                .param(unlinkedStoreId)
                .query(String.class)
                .single();

        assertThat(mappingResult.storesLinked()).isEqualTo(2);
        assertThat(mappingResult.storesUnlinked()).isEqualTo(1);
        assertThat(eligibleStores).isEqualTo(2);
        assertThat(ideaOffer.effectivePrice())
                .isEqualByComparingTo("120");
        assertThat(rodaOffer.effectivePrice())
                .isEqualByComparingTo("90");
        assertThat(ideaOffer.storeFormatCode())
                .isEqualTo("IDEA_LOCATION");
        assertThat(unlinkedStatus)
                .isEqualTo("false:PRICE_FORMAT_NOT_VERIFIED");
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
                            geocoding_reviewed_at,
                            pricing_eligible,
                            pricing_ineligibility_reason
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
                               NOW(),
                               TRUE,
                               NULL
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

    private void registerPriceTestRetailer(
            String retailerCode,
            String csvPath
    ) {
        String datasetUrl = "http://127.0.0.1:"
                + csvServer.getAddress().getPort()
                + csvPath;

        jdbcClient.sql("""
                        INSERT INTO app.retailer (
                            code,
                            name,
                            dataset_url
                        )
                        VALUES (?, ?, ?)
                        """)
                .param(1, retailerCode)
                .param(2, retailerCode + " test")
                .param(3, datasetUrl)
                .update();
    }

    private BigDecimal currentRegularPrice(
            String retailerCode,
            String barcode,
            String retailerFormatName
    ) {
        return jdbcClient.sql("""
                        SELECT offer.regular_price
                        FROM app.current_price_offer AS offer
                        JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = ?
                          AND product.barcode = ?
                          AND offer.retailer_format_name = ?
                        """)
                .param(1, retailerCode)
                .param(2, barcode)
                .param(3, retailerFormatName)
                .query(BigDecimal.class)
                .single();
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
