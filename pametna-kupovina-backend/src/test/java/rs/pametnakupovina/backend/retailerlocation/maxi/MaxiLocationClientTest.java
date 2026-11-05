package rs.pametnakupovina.backend.retailerlocation.maxi;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MaxiLocationClientTest {

    @Test
    void readsCompleteOfficialStoreSearch() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andRespond(withSuccess(
                        response(1, """
                                {
                                  "id":"S508",
                                  "localizedName":"Maxi 508",
                                  "groceryStoreType":"MAXI",
                                  "address":{
                                    "line1":"Kneza Mihaila 84-86",
                                    "town":"Valjevo"
                                  },
                                  "geoPoint":{
                                    "latitude":44.274,
                                    "longitude":19.88
                                  }
                                }
                                """),
                        MediaType.APPLICATION_JSON
                ));

        var locations = new MaxiLocationClient(
                builder.build(),
                "https://maxi.test/graphql"
        ).fetchLocations();

        assertThat(locations).singleElement().satisfies(location -> {
            assertThat(location.code()).isEqualTo("S508");
            assertThat(location.city()).isEqualTo("Valjevo");
            assertThat(location.storeType()).isEqualTo("MAXI");
        });
        server.verify();
    }

    @Test
    void rejectsPartialResultBeforeLocationsCanBeDeactivated() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andRespond(withSuccess(
                        response(593, """
                                {
                                  "id":"S508",
                                  "localizedName":"Maxi 508",
                                  "groceryStoreType":"MAXI",
                                  "address":{"line1":"Adresa","town":"Grad"},
                                  "geoPoint":{"latitude":44.2,"longitude":19.8}
                                }
                                """),
                        MediaType.APPLICATION_JSON
                ));
        // The next page brings nothing more, so the set stays short.
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andExpect(jsonPath("$.variables.currentPage").value(1))
                .andRespond(withSuccess(
                        response(593, ""),
                        MediaType.APPLICATION_JSON
                ));

        MaxiLocationClient client = new MaxiLocationClient(
                builder.build(),
                "https://maxi.test/graphql"
        );
        assertThatThrownBy(client::fetchLocations)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1/593");
        server.verify();
    }

    @Test
    void followsPagesWhenLocatorServesFewerStoresThanAsked() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andExpect(jsonPath("$.variables.currentPage").value(0))
                .andRespond(withSuccess(
                        response(2, store("S508", "MAXI")),
                        MediaType.APPLICATION_JSON
                ));
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andExpect(jsonPath("$.variables.currentPage").value(1))
                .andRespond(withSuccess(
                        response(2, store("S512", "SHOPNGO")),
                        MediaType.APPLICATION_JSON
                ));

        var locations = new MaxiLocationClient(
                builder.build(),
                "https://maxi.test/graphql"
        ).fetchLocations();

        assertThat(locations).extracting(MaxiApiLocation::code)
                .containsExactly("S508", "S512");
        server.verify();
    }

    @Test
    void keepsStoreWithMissingFieldsSoOnlyThatStoreIsSkipped() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andRespond(withSuccess(
                        response(2, store("S508", "MAXI") + ","
                                + """
                                {
                                  "id":"S999",
                                  "localizedName":"Maxi 999",
                                  "groceryStoreType":"MAXI",
                                  "address":{"line1":"Adresa"}
                                }
                                """),
                        MediaType.APPLICATION_JSON
                ));

        var locations = new MaxiLocationClient(
                builder.build(),
                "https://maxi.test/graphql"
        ).fetchLocations();

        assertThat(locations).hasSize(2);
        assertThat(locations.get(1).city()).isNull();
        assertThat(locations.get(1).latitude()).isNaN();
        server.verify();
    }

    @Test
    void reportsGraphqlErrorInsteadOfEmptyResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andRespond(withSuccess(
                        """
                        {"errors":[{"message":"Unknown field storeSearchJSON"}]}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        MaxiLocationClient client = new MaxiLocationClient(
                builder.build(),
                "https://maxi.test/graphql"
        );
        assertThatThrownBy(client::fetchLocations)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown field storeSearchJSON");
        server.verify();
    }

    @Test
    void asksAgainWhenLocatorFailsOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andRespond(withServerError());
        server.expect(once(), requestTo("https://maxi.test/graphql"))
                .andRespond(withSuccess(
                        response(1, store("S508", "MAXI")),
                        MediaType.APPLICATION_JSON
                ));

        var locations = new MaxiLocationClient(
                builder.build(),
                "https://maxi.test/graphql"
        ).fetchLocations();

        assertThat(locations).extracting(MaxiApiLocation::code)
                .containsExactly("S508");
        server.verify();
    }

    private String store(String id, String type) {
        return """
                {
                  "id":"%s",
                  "localizedName":"Maxi",
                  "groceryStoreType":"%s",
                  "address":{"line1":"Adresa","town":"Grad"},
                  "geoPoint":{"latitude":44.2,"longitude":19.8}
                }
                """.formatted(id, type);
    }

    private String response(int totalResults, String store) {
        return """
                {
                  "data": {
                    "storeSearchJSON": {
                      "pagination":{"totalResults":%d},
                      "stores":[%s]
                    }
                  }
                }
                """.formatted(totalResults, store);
    }
}
