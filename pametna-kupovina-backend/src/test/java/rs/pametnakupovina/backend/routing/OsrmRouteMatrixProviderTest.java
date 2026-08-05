package rs.pametnakupovina.backend.routing;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OsrmRouteMatrixProviderTest {

    @Test
    void returnsRoadMatrixAndReusesOnlyPublicStorePairs()
            throws IOException {
        AtomicInteger requests = new AtomicInteger();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext("/", exchange -> {
            requests.incrementAndGet();

            byte[] response = """
                    {
                      "code": "Ok",
                      "distances": [[0, 2500], [2600, 0]],
                      "durations": [[0, 300], [320, 0]]
                    }
                    """.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        server.start();

        try {
            OsrmRoutingProperties properties =
                    new OsrmRoutingProperties();

            properties.setBaseUrl(
                    "http://127.0.0.1:"
                            + server.getAddress().getPort()
                            + "/table/v1"
            );

            OsrmRouteMatrixProvider provider =
                    new OsrmRouteMatrixProvider(
                            properties,
                            new PublicRoutePairCache(),
                            new StraightLineRouteMatrixProvider(),
                            RestClient.create()
                    );

            List<RouteWaypoint> stores = List.of(
                    new RouteWaypoint("STORE:1", 44.0, 19.0),
                    new RouteWaypoint("STORE:2", 44.1, 19.1)
            );

            RouteMatrix first = provider.calculate(stores);
            RouteMatrix cached = provider.calculate(stores);

            assertThat(first.providerCode()).isEqualTo("OSRM");
            assertThat(first.approximate()).isFalse();
            assertThat(first.entry("STORE:1", "STORE:2")
                    .distanceMeters()).isEqualTo(2_500L);
            assertThat(first.entry("STORE:1", "STORE:2")
                    .durationSeconds()).isEqualTo(300L);

            assertThat(cached.providerCode()).isEqualTo("OSRM_CACHE");
            assertThat(cached.entry("STORE:2", "STORE:1")
                    .durationSeconds()).isEqualTo(320L);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }
}
