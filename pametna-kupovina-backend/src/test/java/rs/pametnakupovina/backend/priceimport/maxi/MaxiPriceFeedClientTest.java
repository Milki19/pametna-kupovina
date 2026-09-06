package rs.pametnakupovina.backend.priceimport.maxi;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MaxiPriceFeedClientTest {

    @Test
    void returnsOnlyConfiguredStoreFiles() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo("https://example.test/graphql"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        """
                        {
                          "data": {
                            "digitalPriceListsByDate": {
                              "items": [
                                {
                                  "name": "assets/pricelist/21-08-2026/508_VALJEVO_20260821.csv",
                                  "path": "/prices/maxi-508.csv",
                                  "lastModified": "2026-08-21T03:00:00Z"
                                },
                                {
                                  "name": "MAXI_999_BEOGRAD_20260821.csv",
                                  "path": "/prices/maxi-999.csv",
                                  "lastModified": "2026-08-21T03:00:00Z"
                                },
                                {
                                  "name": "508_DUNAV_BEOGRAD_20260821.csv",
                                  "path": "/prices/wrong-maxi-508.csv",
                                  "lastModified": "2026-08-21T03:00:00Z"
                                }
                              ],
                              "nextMarker": null
                            }
                          }
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        MaxiPriceFeedClient client = new MaxiPriceFeedClient(
                builder.build(),
                "https://example.test/graphql",
                "https://static.example.test/",
                "508,538",
                "508=508_VALJEVO_,538=MAXI_538_VALJEVO_",
                "508=S841,538=S538",
                7
        );

        List<MaxiPriceFile> files = client.findLatestFiles(
                LocalDate.of(2026, 8, 21)
        );

        assertThat(files).containsExactly(new MaxiPriceFile(
                "S841",
                "assets/pricelist/21-08-2026/508_VALJEVO_20260821.csv",
                "https://static.example.test/prices/maxi-508.csv",
                LocalDate.of(2026, 8, 21)
        ));
        server.verify();
    }
}
