package rs.pametnakupovina.backend.shoppinglist;

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
import rs.pametnakupovina.backend.TestCallers;
import rs.pametnakupovina.backend.priceimport.PriceImportService;
import rs.pametnakupovina.backend.product.CanonicalProductSearchService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fifth round, from the test of 20.10.2026: a message in words the
 * earlier tests never used. Only 5 of 14 lines were found without a click,
 * feta became a young cheese, 3,2 % milk 1,5 %, "šampon za decu" a shampoo
 * against dandruff and "paradajz i krastavac" only a cucumber (V140).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class UsualKindFifthRoundTest {

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

    private static final String TOKEN = "usual-kind-5";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    /** The second message of the test, as it was pasted. */
    private static final String MESSAGE = """
            Treba nam:
            - 1,5l kisele vode x2
            - pola kg mlevenog mesa
            - feta sir
            - paradajz i krastavac
            - 2 kg brašna T-500
            - pelene 4 (za bebu)
            - šampon za decu
            - kafa (Grand)
            - mleko 3,2% 2l
            - tortilje
            - vlažne maramice
            - maslinovo ulje
            - hrana za pse
            """;

    private static final String WATER = "KISELA VODA PROLOM 1,5L";
    private static final String VODKA = "VOTKA SMIRNOFF 0,7L";
    private static final String ACID = "SONA KISELINA 1L";
    private static final String MINCED = "MLEVENO MESO JUNECE 500G";
    private static final String BISCUIT = "MLEVENI KEKS 300G";
    private static final String FETA = "SIR FETA DODONI 200G";
    private static final String YOUNG_CHEESE = "MLADI SIR 250G";
    private static final String TOMATO = "PARADAJZ";
    private static final String CUCUMBER = "KRASTAVAC";
    private static final String FLOUR = "BRASNO T-500 1KG";
    private static final String BIG_FLOUR = "BRASNO T-500 5KG";
    private static final String DIAPERS = "PAMPERS PELENE 4 MAXI 46KOM";
    private static final String KIDS_SHAMPOO = "JOHNSONS SAMPON ZA DECU 300ML";
    private static final String DANDRUFF_SHAMPOO = "SAMPON PROTIV PERUTI 400ML";
    private static final String COFFEE = "GRAND KAFA 200G";
    private static final String MILK = "MLEKO 3,2% MM 1L";
    private static final String LIGHT_MILK = "MLEKO 1,5% MM 1L";
    private static final String TORTILLAS = "TORTILJA PSENICNA 6/1 370G";
    private static final String WIPES = "MARAMICE VLAZNE BABY 72KOM";
    private static final String OLIVE_OIL = "MASLINOVO ULJE EKSTRA DEVICANSKO 1L";
    private static final String POMACE_OIL = "ULJE OD KOMINE MASLINE 1L";
    private static final String DOG_FOOD = "HRANA ZA PSE PEDIGREE 400G";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static final String CATALOGUE = HEADER
            + row(WATER, "79,99")
            + row(VODKA, "1299,99")
            + row(ACID, "59,99")
            + row(MINCED, "549,99")
            + row(BISCUIT, "149,99")
            + row(FETA, "329,99")
            + row(YOUNG_CHEESE, "199,99")
            + "3;Sveže voće i povrće;" + TOMATO + ";;;KG;Test format;189,99;;13-09-2026;189,99;;;10\n"
            + "3;Sveže voće i povrće;" + CUCUMBER + ";;;KG;Test format;119,99;;13-09-2026;119,99;;;10\n"
            + row(FLOUR, "52,99")
            + row(BIG_FLOUR, "244,99")
            + row(DIAPERS, "1599,99")
            + row(KIDS_SHAMPOO, "299,99")
            + row(DANDRUFF_SHAMPOO, "199,99")
            + row(COFFEE, "289,99")
            + row(MILK, "159,99")
            + row(LIGHT_MILK, "129,99")
            + row(TORTILLAS, "199,99")
            + row(WIPES, "149,99")
            + row(OLIVE_OIL, "1299,99")
            + row(POMACE_OIL, "799,99")
            + row(DOG_FOOD, "189,99");

    /** A shop without feta and without 3,2 % milk. */
    private static final String SMALL_CATALOGUE = HEADER
            + row(YOUNG_CHEESE, "189,99")
            + row(LIGHT_MILK, "119,99");

    /**
     * Proba V140 on a copy of the catalogue: a word found anywhere in a name
     * bought cat food for "losos", a wafer for "kakao", a baguette for
     * "semenke", AAA for AA, 12 % cream for 20 % (Lidl's "MK20") and sour
     * cream for "kajmak".
     */
    private static final String SALMON_CAT_FOOD = "VLAZNA HRANA ZA MACKE LOSOS 100G";
    private static final String SALMON = "LOSOS DIMLJENI 100G";
    private static final String COCOA_WAFER = "ROGER VAFEL PROIZ. KAKAO 40G";
    private static final String COCOA = "KAKAO PRAH 100G";
    private static final String SEED_BAGUETTE = "MINI BAGET SA SEMENKAMA 110G";
    private static final String SEEDS = "SEMENKE SUNCOKRETA 200G";
    private static final String AAA = "ALKALNE BATERIJE AAA MK60 12KOM";
    private static final String AA = "ALKALNE BATERIJE AA 4KOM";
    private static final String CREAM_12 = "KISELA PAVLAKA 12% MK20 180G";
    private static final String CREAM_20 = "PAVLAKA 20% 200G";
    private static final String COOKING_CREAM = "PAVLAKA ZA KUVANJE 200ML";
    private static final String KAJMAK = "KAJMAK 200G";
    private static final String SALMON_PATE = "PASTETA OD LOSOSA 60G";
    private static final String COCOA_DONUT = "DONUT SA KAKAO GLAZUROM 49G";
    private static final String BRANDED_SALMON_PATE = "PASTETA PATELINA LOSOS 60G";
    private static final String COCOA_CROISSANT = "KROASAN MONSTER KAKAO 50G";
    private static final String SOY_DRINK = "NAPITAK SOJA VANILA 250ML ALPRO";
    private static final String FOIL = "ALUMINIJUMSKA FOLIJA 20M";
    private static final String HAM_IN_FOIL = "SUNKA U FOLIJI 5KG";
    private static final String CORN_FLAKES = "CORN FLAKES 375G";
    private static final String SPARKLING_WATER = "MINERALNA GAZIRANA VODA 1,5L";
    private static final String STILL_WATER = "MINERALNA NEGAZIRANA VODA 1,5L";

    private static int barcode = 5000;

    private static final String PROBE_CATALOGUE = HEADER
            + row(SALMON_CAT_FOOD, "59,99")
            + row(SALMON, "399,99")
            + row(COCOA_WAFER, "39,99")
            + row(COCOA, "149,99")
            + row(SEED_BAGUETTE, "49,99")
            + row(SEEDS, "99,99")
            + row(AAA, "299,99")
            + row(AA, "349,99")
            + row(CREAM_12, "79,99")
            + row(CREAM_20, "129,99")
            + row(COOKING_CREAM, "99,99")
            + row(KAJMAK, "299,99")
            + row(SALMON_PATE, "89,99")
            + row(COCOA_DONUT, "49,99")
            + row(BRANDED_SALMON_PATE, "79,99")
            + row(COCOA_CROISSANT, "59,99")
            + row(SOY_DRINK, "189,99")
            + row(FOIL, "149,99")
            + row(HAM_IN_FOIL, "4999,99")
            + row(CORN_FLAKES, "229,99")
            + row(SPARKLING_WATER, "49,99")
            + row(STILL_WATER, "39,99");

    /** Maxi in the live check: only a pâté and a croissant say salmon and cocoa. */
    private static final String MADE_OF_IT_CATALOGUE = HEADER
            + row(BRANDED_SALMON_PATE, "79,99")
            + row(COCOA_CROISSANT, "59,99");

    private static final String CREAM_CHEESE = "KREM SIR PHILADELPHIA 175G";
    private static final String SUGAR = "SECER KRISTAL 1KG";
    private static final String VANILLA_SUGAR = "VANILIN SECER 10G";

    /** Provera uživo 23.10.: "krem sir" and "vanilin šećer" had no price. */
    private static final String QUALIFIED_CATALOGUE = HEADER
            + row(CREAM_CHEESE, "249,99")
            + row(YOUNG_CHEESE, "199,99")
            + row(SUGAR, "119,99")
            + row(VANILLA_SUGAR, "19,99");

    private static String row(String name, String price) {
        barcode++;
        return ";;" + name + ";Testna;86000000" + barcode + ";KOM"
                + ";Test format;" + price + ";;13-09-2026;" + price + ";;;20\n";
    }

    private static HttpServer csvServer;

    @Autowired
    private TestCallers callers;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private ShoppingListMatchingService matchingService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private CanonicalProductSearchService searchService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        for (Map.Entry<String, String> list : Map.of(
                "/round5.csv", CATALOGUE,
                "/round5b.csv", SMALL_CATALOGUE,
                "/round5c.csv", PROBE_CATALOGUE,
                "/round5d.csv", MADE_OF_IT_CATALOGUE,
                "/round5e.csv", QUALIFIED_CATALOGUE).entrySet()) {
            csvServer.createContext(list.getKey(), exchange -> {
                byte[] body = list.getValue().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        }
        csvServer.setExecutor(Executors.newSingleThreadExecutor());
        csvServer.start();
    }

    @AfterAll
    static void stopCsvServer() {
        if (csvServer != null) {
            csvServer.stop(0);
        }
    }

    private long shop(String code, String path) {
        return jdbcClient.sql("SELECT id FROM app.store WHERE external_code = ?")
                .param(code + "-1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> newShop(code, path));
    }

    private long newShop(String code, String path) {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(code)
                .param("Round " + code)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + path)
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices(code).status())
                .isEqualTo("SUCCEEDED");

        long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name)
                        VALUES (?, 'TEST', 'Test format')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        return jdbcClient.sql("""
                        INSERT INTO app.store (retailer_id, store_format_id, external_code, name, address, city, active)
                        VALUES (?, ?, ?, 'Objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .param(code + "-1")
                .query(Long.class)
                .single();
    }

    private long bigShop() {
        return shop("ROUND5", "/round5.csv");
    }

    private long smallShop() {
        bigShop();
        return shop("ROUND5B", "/round5b.csv");
    }

    private long pasted(String text) {
        var list = shoppingListService.create(
                new CreateShoppingListRequest("Test 20.10."), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(), callers.account(TOKEN), new PasteShoppingListItemsRequest(text));
        return list.id();
    }

    @Test
    void theWholeMessageIsFoundWithoutAClick() {
        bigShop();
        long listId = pasted(MESSAGE);

        var matching = matchingService.match(listId, callers.caller(TOKEN), true);

        assertThat(matching.totalItems()).isEqualTo(14);
        assertThat(matching.items())
                .as(matching.items().stream()
                        .map(item -> item.requestedName() + " " + item.matchingRule() + " " + item.matchingStatus())
                        .toList().toString())
                .allMatch(item -> item.matchingStatus() == ShoppingItemMatchingStatus.CONFIRMED
                        || item.matchingStatus() == ShoppingItemMatchingStatus.AUTO_MATCHED);
    }

    @Test
    void thePlanBuysWhatTheMessageAsksFor() {
        long shop = bigShop();
        long listId = pasted(MESSAGE);
        matchingService.match(listId, callers.caller(TOKEN), true);

        Map<String, StoreItemOffer> offers = offerRepository
                .findPriceListOffers(listId, List.of(shop), PRICE_DATE)
                .stream()
                .collect(Collectors.toMap(StoreItemOffer::requestedName, offer -> offer, (a, b) -> a));
        String seen = offers.values().stream()
                .map(offer -> offer.requestedName() + " -> " + offer.productName()
                        + (offer.purchaseQuantity() == null ? "" : " x" + offer.purchaseQuantity().packages()))
                .sorted()
                .collect(Collectors.joining("\n"));

        assertThat(offers).as(seen).hasSize(14);
        assertThat(offers.values()).as(seen).allMatch(StoreItemOffer::available);
        assertThat(productFor(offers, "kisel")).as(seen).isEqualTo(WATER);
        assertThat(productFor(offers, "mesa")).as(seen).isEqualTo(MINCED);
        assertThat(productFor(offers, "feta")).as(seen).isEqualTo(FETA);
        assertThat(productFor(offers, "paradajz")).as(seen).isEqualTo(TOMATO);
        assertThat(productFor(offers, "krastavac")).as(seen).isEqualTo(CUCUMBER);
        assertThat(productFor(offers, "brašn")).as(seen).isEqualTo(FLOUR);
        assertThat(offerFor(offers, "brašn").purchaseQuantity().packages()).isEqualByComparingTo("2");
        assertThat(productFor(offers, "pelene")).as(seen).isEqualTo(DIAPERS);
        assertThat(productFor(offers, "šampon")).as(seen).isEqualTo(KIDS_SHAMPOO);
        assertThat(productFor(offers, "kafa")).as(seen).isEqualTo(COFFEE);
        assertThat(productFor(offers, "mleko")).as(seen).isEqualTo(MILK);
        assertThat(offerFor(offers, "mleko").purchaseQuantity().packages()).isEqualByComparingTo("2");
        assertThat(productFor(offers, "tortilje")).as(seen).isEqualTo(TORTILLAS);
        assertThat(productFor(offers, "maramice")).as(seen).isEqualTo(WIPES);
        assertThat(productFor(offers, "maslinovo")).as(seen).isEqualTo(OLIVE_OIL);
        assertThat(productFor(offers, "pse")).as(seen).isEqualTo(DOG_FOOD);
        assertThat(offers.values()).noneMatch(StoreItemOffer::wantedMissing);
    }

    /** A shop without feta still offers a cheese, and says it is another one. */
    @Test
    void aShopWithoutWhatIsAskedOffersASubstituteAndSaysSo() {
        long small = smallShop();
        long listId = pasted("feta sir\nmleko 3,2% 1l");
        matchingService.match(listId, callers.caller(TOKEN), true);

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(listId, List.of(small), PRICE_DATE);

        assertThat(offers).extracting(StoreItemOffer::productName)
                .containsExactlyInAnyOrder(YOUNG_CHEESE, LIGHT_MILK);
        assertThat(offers).allMatch(StoreItemOffer::wantedMissing);
    }

    /** Step 1 searched the whole line: votka for "kisele vode", keks for meat. */
    @Test
    void stepOneSearchesTheThingNotTheAmount() {
        bigShop();

        var water = searchService.search("1,5l kisele vode", 0, 5, true, null, false);
        assertThat(water.items().getFirst().name()).containsIgnoringCase("kisela voda");

        var meat = searchService.search("0,5 kg mlevenog mesa", 0, 5, true, null, false);
        assertThat(meat.items().getFirst().name()).containsIgnoringCase("mleveno meso");
    }

    /** What the probe on a copy bought wrongly, each from a name that says it in passing. */
    @Test
    void aWordInPassingIsNotTheThingAsked() {
        long probe = shop("ROUND5C", "/round5c.csv");
        long listId = pasted("losos\nkakao\nsemenke\nbaterije AA\npavlaka 20%\nkajmak\nkisela voda"
                + "\nsojino mleko\nfolija za hranu\nkukuruzne pahuljice");
        matchingService.match(listId, callers.caller(TOKEN), true);

        Map<String, StoreItemOffer> offers = offerRepository
                .findPriceListOffers(listId, List.of(probe), PRICE_DATE)
                .stream()
                .collect(Collectors.toMap(StoreItemOffer::requestedName, offer -> offer, (a, b) -> a));
        String seen = offers.values().stream()
                .map(offer -> offer.requestedName() + " -> " + offer.productName()
                        + (offer.wantedMissing() ? " (zamena)" : ""))
                .sorted()
                .collect(Collectors.joining("\n"));

        assertThat(productFor(offers, "losos")).as(seen).isEqualTo(SALMON);
        assertThat(productFor(offers, "kakao")).as(seen).isEqualTo(COCOA);
        assertThat(productFor(offers, "semenke")).as(seen).isEqualTo(SEEDS);
        assertThat(productFor(offers, "baterije")).as(seen).isEqualTo(AA);
        assertThat(productFor(offers, "pavlaka")).as(seen).isEqualTo(CREAM_20);
        assertThat(productFor(offers, "kajmak")).as(seen).isEqualTo(KAJMAK);
        // Names that say it otherwise (proba V140, no price before).
        assertThat(productFor(offers, "sojino")).as(seen).isEqualTo(SOY_DRINK);
        assertThat(productFor(offers, "folija")).as(seen).isEqualTo(FOIL);
        assertThat(productFor(offers, "pahuljice")).as(seen).isEqualTo(CORN_FLAKES);
        // Kisela voda is mineral water by another name, not a substitute.
        assertThat(productFor(offers, "kisela")).as(seen).isEqualTo(SPARKLING_WATER);
        assertThat(offers.values()).as(seen).noneMatch(StoreItemOffer::wantedMissing);
    }

    /** "Pasteta Patelina losos" is a pâté, not the salmon asked (provera uživo 23.10.). */
    @Test
    void whatIsMadeOfTheWordIsNotTheWord() {
        long shop = shop("ROUND5D", "/round5d.csv");
        long listId = pasted("losos\nkakao");
        matchingService.match(listId, callers.caller(TOKEN), true);

        var offers = offerRepository.findPriceListOffers(listId, List.of(shop), PRICE_DATE);

        assertThat(offers)
                .as(offers.stream().map(StoreItemOffer::productName).toList().toString())
                .allMatch(offer -> offer.productName() == null || offer.wantedMissing());
    }

    /** "Krem sir" is cream cheese and "vanilin šećer" vanilla sugar, not plain ones. */
    @Test
    void theWordBeforeTheKindSaysWhichOne() {
        long shop = shop("ROUND5E", "/round5e.csv");
        // Brands somewhere in the catalogue, as on the server.
        jdbcClient.sql("""
                        INSERT INTO app.brand (normalized_name, display_name)
                        VALUES ('krem', 'Krem'), ('vanilin', 'Vanilin')
                        ON CONFLICT (normalized_name) DO NOTHING
                        """).update();
        long listId = pasted("krem sir\nvanilin šećer");
        matchingService.match(listId, callers.caller(TOKEN), true);

        Map<String, StoreItemOffer> offers = offerRepository
                .findPriceListOffers(listId, List.of(shop), PRICE_DATE)
                .stream()
                .collect(Collectors.toMap(StoreItemOffer::requestedName, offer -> offer, (a, b) -> a));
        String seen = offers.values().stream()
                .map(offer -> offer.requestedName() + " -> " + offer.productName()
                        + (offer.wantedMissing() ? " (zamena)" : ""))
                .sorted()
                .collect(Collectors.joining("\n"))
                + "\n" + jdbcClient.sql("""
                        SELECT string_agg(name || ' | ' || flexible_category || ' | '
                               || COALESCE(wanted_name_pattern, '-') || ' | ' || matching_status, E'\n')
                        FROM app.shopping_list_item WHERE shopping_list_id = ?
                        """).param(listId).query(String.class).single();

        assertThat(productFor(offers, "krem")).as(seen).isEqualTo(CREAM_CHEESE);
        assertThat(productFor(offers, "vanilin")).as(seen).isEqualTo(VANILLA_SUGAR);
        assertThat(offers.values()).as(seen).noneMatch(StoreItemOffer::wantedMissing);
    }

    private static StoreItemOffer offerFor(Map<String, StoreItemOffer> offers, String word) {
        return offers.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase().contains(word))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nema stavke sa „" + word + "“: " + offers.keySet()));
    }

    private static String productFor(Map<String, StoreItemOffer> offers, String word) {
        return offerFor(offers, word).productName();
    }
}
