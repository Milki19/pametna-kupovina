package rs.pametnakupovina.backend.place;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.market.TestMarkets;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An address typed on the web becomes a starting point in the account's country. */
class PlaceSearchServiceTest {

    private HttpServer server;
    private final List<String> asked = new ArrayList<>();
    private String answer = "[]";
    private int status = 200;
    private PlaceSearchService service;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            asked.add(exchange.getRequestURI().getRawQuery() + " UA=" + exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        };
        server.createContext("/search", handler);
        server.createContext("/reverse", handler);
        server.start();

        MarketRepository markets = mock(MarketRepository.class);
        when(markets.forAccount(7L)).thenReturn(TestMarkets.serbia());
        service = new PlaceSearchService(markets,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/search",
                "PametnaKupovina/test", 0, 5);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void anAddressBecomesPointsInTheAccountsCountry() {
        answer = """
                [{"lat":"44.8180","lon":"20.5050","display_name":"Karaburma, Beograd, Srbija"},
                 {"lat":"x","lon":"20.1","display_name":"broken"},
                 {"lat":"44.1","lon":"20.1","display_name":""}]
                """;

        var places = service.search(7L, "  Karaburma,   Beograd ");

        assertThat(places).containsExactly(
                new PlaceSearchService.Place("Karaburma, Beograd, Srbija", 44.818, 20.505));
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst())
                .contains("q=Karaburma,%20Beograd")
                .contains("countrycodes=rs")
                .contains("format=jsonv2")
                .contains("UA=PametnaKupovina/test");
    }

    @Test
    void theBestKnownPlaceComesFirstWithAShortName() {
        // Nominatim, 14.10.2026: Belgrade's street is "Kneza Mihaila" and came last.
        answer = """
                [{"lat":"43.7","lon":"20.6","importance":0.00006,
                  "display_name":"10, Knez Mihailova, Cvetke, Grad Kraljevo, Raški upravni okrug, Centralna Srbija, Srbija",
                  "address":{"house_number":"10","road":"Knez Mihailova","village":"Cvetke","municipality":"Grad Kraljevo"}},
                 {"lat":"44.27","lon":"19.89","importance":0.00006,
                  "display_name":"10, Knez Mihailova, Tešnjar, Valjevo, Grad Valjevo, Kolubarski upravni okrug, Srbija",
                  "address":{"house_number":"10","road":"Knez Mihailova","suburb":"Tešnjar","city":"Valjevo"}},
                 {"lat":"44.27","lon":"19.89","importance":0.00006,
                  "display_name":"Flora, 10, Knez Mihailova, Tešnjar, Valjevo, Srbija",
                  "address":{"shop":"Flora","house_number":"10","road":"Knez Mihailova","suburb":"Tešnjar","city":"Valjevo"}},
                 {"lat":"44.816","lon":"20.459","importance":0.00008,
                  "display_name":"10, Kneza Mihaila, Skadarlija, Dorćol, Beograd (Stari grad), Beograd, Gradska opština Stari grad, Grad Beograd, Centralna Srbija, 11000, Srbija",
                  "address":{"house_number":"10","road":"Kneza Mihaila","neighbourhood":"Skadarlija","quarter":"Dorćol","suburb":"Beograd (Stari grad)","city":"Beograd"}}]
                """;

        var places = service.search(7L, "Knez Mihailova 10");

        assertThat(places).extracting(PlaceSearchService.Place::name).containsExactly(
                "Kneza Mihaila 10, Dorćol, Beograd",
                "Knez Mihailova 10, Cvetke, Grad Kraljevo",
                "Knez Mihailova 10, Tešnjar, Valjevo");
        assertThat(asked.getFirst()).contains("addressdetails=1").contains("limit=15");
    }

    @Test
    void theSameSearchIsAnsweredFromMemory() {
        answer = "[{\"lat\":\"44.8\",\"lon\":\"20.4\",\"display_name\":\"Vračar\"}]";

        service.search(7L, "Vračar");
        service.search(7L, "vračar");

        assertThat(asked).hasSize(1);
    }

    @Test
    void tooShortOrUnavailableSaysSoInWords() {
        assertThatThrownBy(() -> service.search(7L, "ab"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("tri slova");

        status = 503;
        assertThatThrownBy(() -> service.search(7L, "Novi Sad"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ne radi");
    }

    @Test
    void aPointFromThePhoneReadsAsAShortAddress() {
        answer = """
                {"lat":"45.2551","lon":"19.8452","display_name":"10, Bulevar oslobođenja, Novi Sad, Srbija",
                 "address":{"house_number":"10","road":"Bulevar oslobođenja","city":"Novi Sad"}}
                """;

        var place = service.nameOf(7L, 45.255123, 19.845234);
        service.nameOf(7L, 45.25511, 19.84519);

        assertThat(place).isEqualTo(new PlaceSearchService.Place("Bulevar oslobođenja 10, Novi Sad", 45.255123, 19.845234));
        // Rounded to about ten metres, so the second point is the same question.
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst()).contains("lat=45.2551").contains("lon=19.8452").contains("zoom=18");
    }

    @Test
    void aPointWithoutAnAddressHasNoName() {
        answer = "{\"error\":\"Unable to geocode\"}";

        assertThat(service.nameOf(7L, 44.0, 21.0)).isNull();
        assertThatThrownBy(() -> service.nameOf(7L, 91, 20))
                .isInstanceOf(ResponseStatusException.class);
    }
}
