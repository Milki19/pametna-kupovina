package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GovernmentDataResourceDiscoveryClientTest {

    @Test
    void discoversPriceDatasetsAcrossCatalogPages() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo(
                        "https://data.gov.rs/api/1/datasets/"
                                + "?q=cenovnici&page_size=2"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {
                              "id": "dataset-maxi",
                              "slug": "maxi-cenovnici",
                              "title": "Cenovnici proizvoda Maxi",
                              "organization": {"name": "Delhaize Serbia"},
                              "page": "https://data.gov.rs/sr/datasets/maxi-cenovnici/",
                              "resources": [
                                {
                                  "id": "maxi-old",
                                  "format": "csv",
                                  "title": "cene-proizvoda-staro.csv",
                                  "url": "https://data.gov.rs/s/resources/maxi/old.csv",
                                  "last_modified": "2026-09-04T04:00:00Z"
                                },
                                {
                                  "id": "maxi-new",
                                  "format": "csv",
                                  "title": "cene-proizvoda.csv",
                                  "url": "https://maxi.example/cene.csv",
                                  "last_modified": "2026-09-05T04:00:00Z"
                                }
                              ]
                            },
                            {
                              "id": "dataset-traffic",
                              "slug": "saobracaj",
                              "title": "Brojanje saobraćaja",
                              "resources": []
                            }
                          ],
                          "next_page": "https://data.gov.rs/api/1/datasets/?q=cenovnici&page=2&page_size=2"
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        server.expect(once(), requestTo(
                        "https://data.gov.rs/api/1/datasets/"
                                + "?q=cenovnici&page=2&page_size=2"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "data": [
                            {
                              "id": "dataset-lidl",
                              "slug": "lidl-cenovnik",
                              "title": "Cenovnik Lidl Srbija",
                              "organization": {"name": "Lidl Srbija"},
                              "resources": [
                                {
                                  "id": "lidl-ean",
                                  "format": "csv",
                                  "title": "ean-05-09-2026.csv",
                                  "url": "https://data.gov.rs/s/resources/lidl/ean.csv",
                                  "last_modified": "2026-09-05T05:00:00Z"
                                },
                                {
                                  "id": "lidl-price",
                                  "format": "csv",
                                  "title": "cene_proizvoda_Lidl.csv",
                                  "url": "https://lidl.example/cene.csv",
                                  "last_modified": "2026-09-05T04:00:00Z"
                                }
                              ]
                            }
                          ],
                          "next_page": null
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        List<GovernmentPriceDataset> result =
                new GovernmentDataResourceDiscoveryClient(
                        builder.build()
                ).discoverPriceDatasets(
                        "https://data.gov.rs/api/1/datasets/"
                                + "?q=cenovnici&page_size=2",
                        5
                );

        assertThat(result)
                .extracting(
                        GovernmentPriceDataset::portalDatasetId,
                        GovernmentPriceDataset::resourceId
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "dataset-maxi",
                                "maxi-new"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                "dataset-lidl",
                                "lidl-price"
                        )
                );
        server.verify();
    }

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

    @Test
    void resolvesOfficialStoreToPriceFormatSpreadsheet() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();

        server.expect(once(), requestTo(
                        "https://data.gov.rs/api/1/datasets/idea-cenovnik/"
                ))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {
                          "resources": [
                            {
                              "format": "xlsx",
                              "title": "idea-opsti-podaci.xlsx",
                              "url": "https://data.gov.rs/s/resources/idea/opsti.xlsx",
                              "last_modified": "2026-09-02T05:00:00Z"
                            },
                            {
                              "format": "xlsx",
                              "title": "idea-maloprodajni-objekti.xlsx",
                              "description": "Pregled cenovnika po objektima",
                              "url": "https://data.gov.rs/s/resources/idea/objekti.xlsx",
                              "last_modified": "2026-09-01T05:00:00Z"
                            },
                            {
                              "format": "csv",
                              "title": "cene-proizvoda.csv",
                              "url": "https://data.gov.rs/s/resources/idea/cene.csv",
                              "last_modified": "2026-09-03T05:00:00Z"
                            }
                          ]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));

        var result = new GovernmentDataResourceDiscoveryClient(
                builder.build()
        ).discoverLatestStoreMappingSpreadsheet(
                "https://data.gov.rs/sr/datasets/idea-cenovnik/"
        );

        assertThat(result.url()).isEqualTo(
                "https://data.gov.rs/s/resources/idea/objekti.xlsx"
        );
        assertThat(result.lastModified()).isEqualTo(
                Instant.parse("2026-09-01T05:00:00Z")
        );
        server.verify();
    }
}
