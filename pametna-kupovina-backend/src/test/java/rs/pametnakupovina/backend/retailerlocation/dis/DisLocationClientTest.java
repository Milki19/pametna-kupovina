package rs.pametnakupovina.backend.retailerlocation.dis;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DisLocationClientTest {

    @Test
    void readsOfficialLocationPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo(
                        "https://www.dis.rs/api/Dis/Locations"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        [
                          {
                            "code": "90031",
                            "name": "Bela Crkva 2",
                            "address": "Jovana Popovića 3",
                            "place": "BELA CRKVA",
                            "openHours": "07-22",
                            "type": "superdis",
                            "latitude": "44.9000",
                            "longitude": "21.4200"
                          }
                        ]
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var locations = new DisLocationClient(
                builder.build(),
                "https://www.dis.rs/api/Dis/Locations"
        ).fetchLocations();

        assertThat(locations).singleElement().satisfies(location -> {
            assertThat(location.code()).isEqualTo("90031");
            assertThat(location.type()).isEqualTo("superdis");
            assertThat(location.latitude()).isEqualTo("44.9000");
        });
        server.verify();
    }
}
