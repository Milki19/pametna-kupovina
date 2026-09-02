package rs.pametnakupovina.backend.retailerlocation.lidl;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LidlLocationClientTest {

    @Test
    void readsCompleteOfficialLocationPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo("https://live.api/stores"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-apikey", "public-browser-key"))
                .andRespond(withSuccess(
                        """
                        {
                          "meta": {
                            "limit": 250,
                            "offset": 0,
                            "total": 1
                          },
                          "items": [
                            {
                              "objectNumber": "RS00152",
                              "storeName": "Valjevo",
                              "address": {
                                "streetName": "Pop Lukina",
                                "streetNumber": "45",
                                "city": "Valjevo",
                                "zip": "14000",
                                "longitude": 19.88163,
                                "latitude": 44.27454
                              },
                              "status": {
                                "name": "open"
                              }
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var locations = new LidlLocationClient(
                builder.build(),
                "https://live.api/stores",
                "public-browser-key"
        ).fetchLocations();

        assertThat(locations).singleElement().satisfies(location -> {
            assertThat(location.objectNumber()).isEqualTo("RS00152");
            assertThat(location.address().city()).isEqualTo("Valjevo");
            assertThat(location.status().name()).isEqualTo("open");
        });
        server.verify();
    }

    @Test
    void rejectsPartialPageBeforeExistingLocationsCanBeDeactivated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo("https://live.api/stores"))
                .andRespond(withSuccess(
                        """
                        {
                          "meta": {
                            "limit": 1,
                            "offset": 0,
                            "total": 86
                          },
                          "items": [
                            {
                              "objectNumber": "RS00152",
                              "storeName": "Valjevo"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        LidlLocationClient client = new LidlLocationClient(
                builder.build(),
                "https://live.api/stores",
                "public-browser-key"
        );

        assertThatThrownBy(client::fetchLocations)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nepotpunu stranicu")
                .hasMessageContaining("1/86");
        server.verify();
    }
}
