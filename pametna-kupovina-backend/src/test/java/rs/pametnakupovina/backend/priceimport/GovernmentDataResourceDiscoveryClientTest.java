package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GovernmentDataResourceDiscoveryClientTest {

    @Test
    void resolvesNewestCsvFromOfficialDatasetApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo(
                        "https://data.gov.rs/api/1/datasets/test-cenovnik/"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "resources": [
                            {
                              "format": "csv",
                              "title": "cene-proizvoda-lidl.csv",
                              "url": "https://data.gov.rs/s/resources/test/cene-proizvoda-lidl.csv",
                              "last_modified": "2026-08-20T04:00:00Z"
                            },
                            {
                              "format": "xlsx",
                              "url": "https://data.gov.rs/s/resources/test/locations.xlsx",
                              "last_modified": "2026-08-26T05:00:00Z"
                            },
                            {
                              "mime": "text/csv",
                              "title": "cene-proizvoda-lidl-novi.csv",
                              "url": "https://data.gov.rs/s/resources/test/cene-proizvoda-lidl-novi.csv",
                              "last_modified": "2026-08-26T04:00:00Z"
                            },
                            {
                              "mime": "text/csv",
                              "title": "ean-26-08-2026.csv",
                              "url": "https://data.gov.rs/s/resources/test/ean-26-08-2026.csv",
                              "last_modified": "2026-08-26T05:00:00Z"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var result = new GovernmentDataResourceDiscoveryClient(
                builder.build()
        ).discoverLatestCsv(
                "https://data.gov.rs/sr/datasets/test-cenovnik/"
        );

        assertThat(result.url()).isEqualTo(
                "https://data.gov.rs/s/resources/test/cene-proizvoda-lidl-novi.csv"
        );
        assertThat(result.lastModified()).isEqualTo(
                Instant.parse("2026-08-26T04:00:00Z")
        );
        server.verify();
    }

    @Test
    void acceptsHttpsPublisherResourceReferencedByOfficialDataset() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo(
                        "https://data.gov.rs/api/1/datasets/lidl-cenovnik/"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "resources": [
                            {
                              "format": "csv",
                              "title": "ean.csv",
                              "url": "https://data.gov.rs/s/resources/lidl/ean.csv",
                              "last_modified": "2026-09-01T06:16:03Z"
                            },
                            {
                              "format": "csv",
                              "title": "cene_proizvoda_Lidl",
                              "url": "https://kompanija.lidl.rs/files/cene_proizvoda_Lidl.csv",
                              "last_modified": "2026-09-01T06:17:03Z"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var result = new GovernmentDataResourceDiscoveryClient(
                builder.build()
        ).discoverLatestCsv(
                "https://data.gov.rs/sr/datasets/lidl-cenovnik/"
        );

        assertThat(result.url()).isEqualTo(
                "https://kompanija.lidl.rs/files/"
                        + "cene_proizvoda_Lidl.csv"
        );
        assertThat(result.lastModified()).isEqualTo(
                Instant.parse("2026-09-01T06:17:03Z")
        );
        server.verify();
    }
}
