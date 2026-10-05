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
        server.createContext("/search", exchange -> {
            asked.add(exchange.getRequestURI().getRawQuery() + " UA=" + exchange.getRequestHeaders().getFirst("User-Agent"));
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
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
}
