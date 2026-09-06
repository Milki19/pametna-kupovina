package rs.pametnakupovina.backend.retailerlocation.univerexport;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UniverexportLocationClientTest {

    @Test
    void decodesOfficialDoubleEncodedJsonPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        JsonMapper mapper = JsonMapper.builder().build();
        String embeddedJson = """
                [["0",{
                  "place_id":"10033",
                  "status":1,
                  "sr_name":"MP033 Sentandrejski put bb, Novi Sad",
                  "address":"Sentandrejski put bb",
                  "grad":"Novi Sad",
                  "format":"Veliki",
                  "lon":"19.831535",
                  "lat":"45.272553"
                }]]
                """;
        server.expect(once(), requestTo("https://univer.test/locations"))
                .andRespond(withSuccess(
                        mapper.writeValueAsString(embeddedJson),
                        MediaType.APPLICATION_JSON
                ));

        var locations = new UniverexportLocationClient(
                builder.build(),
                mapper,
                "https://univer.test/locations"
        ).fetchLocations();

        assertThat(locations).singleElement().satisfies(location -> {
            assertThat(location.code()).isEqualTo("10033");
            assertThat(location.city()).isEqualTo("Novi Sad");
            assertThat(location.format()).isEqualTo("Veliki");
            assertThat(location.active()).isTrue();
        });
        server.verify();
    }
}
