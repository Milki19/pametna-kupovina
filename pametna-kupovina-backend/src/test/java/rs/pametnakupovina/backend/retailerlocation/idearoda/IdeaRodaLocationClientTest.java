package rs.pametnakupovina.backend.retailerlocation.idearoda;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdeaRodaLocationClientTest {

    @Test
    void mergesBothOfficialLocatorsWithoutInventingPriceMapping() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        server.expect(once(), requestTo("https://idea.test/locations"))
                .andRespond(withSuccess(
                        """
                        {
                          "success":true,
                          "markers":[{
                            "lat":"44.270271",
                            "lng":"19.886746",
                            "type":"IDEA",
                            "address":"Karađorđeva 62"
                          }]
                        }
                        """,
                        MediaType.APPLICATION_JSON
                ));
        server.expect(once(), requestTo("https://roda.test/locations"))
                .andRespond(withSuccess(
                        """
                        <html><body>
                        <script id="__NEXT_DATA__" type="application/json">
                        {"props":{"pageProps":{"page":{"attributes":{
                          "blocks":[{"stores":[{"item":{
                            "title":"407",
                            "address":"Bul. palih boraca 91-92 br.1",
                            "city":"Valjevo",
                            "latitude":44.2689,
                            "longitude":19.898
                          }}]}]
                        }}}}}
                        </script>
                        </body></html>
                        """,
                        MediaType.TEXT_HTML
                ));

        var locations = new IdeaRodaLocationClient(
                builder.build(),
                JsonMapper.builder().build(),
                "https://idea.test/locations",
                "https://roda.test/locations"
        ).fetchLocations();

        assertThat(locations).hasSize(2);
        assertThat(locations).extracting(location -> location.code())
                .containsExactly(
                        "IDEA_GEO_44_270271_19_886746",
                        "RODA_407"
                );
        assertThat(locations.get(1).city()).isEqualTo("Valjevo");
        assertThat(locations.get(1).format()).isEqualTo("RODA");
        server.verify();
    }
}
