package rs.pametnakupovina.backend.retailerlocation.univerexport;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void oneIncompleteShopNoLongerFailsTheWholeSync() {
        String embeddedJson = """
                [["0",{"place_id":"10001","status":1,
                  "sr_name":"MP001 Bulevar oslobođenja 1, Novi Sad",
                  "address":"","grad":"Novi Sad","format":"Super",
                  "lon":"19.8","lat":"45.2"}],
                 ["1",{"place_id":"10002","status":0,
                  "sr_name":"MP002 Zatvorena","format":"Mini"}],
                 ["2",{"place_id":"10003","status":1,
                  "sr_name":"MP003 Bez koordinata, Beograd",
                  "address":"Bez koordinata","grad":"Beograd",
                  "format":"Mini","lon":"","lat":null}],
                 ["3",{"place_id":"10004","status":1,
                  "sr_name":"MP004 Glavna 4, Subotica",
                  "address":null,"grad":"","format":"Mini",
                  "lon":"19.6","lat":"46.1"}]]
                """;

        var locations = fetch(embeddedJson);

        assertThat(locations).extracting(UniverexportApiLocation::code)
                .containsExactly("10001", "10004");
        assertThat(locations.get(0).address())
                .isEqualTo("Bulevar oslobođenja 1");
        assertThat(locations.get(1).address()).isEqualTo("Glavna 4");
        assertThat(locations.get(1).city()).isEqualTo("Subotica");
    }

    @Test
    void manyIncompleteShopsStillFailRatherThanDeactivateThem() {
        StringBuilder rows = new StringBuilder("[");
        for (int index = 0; index < 4; index++) {
            rows.append(index == 0 ? "" : ",").append("""
                    ["%d",{"place_id":"2000%d","status":1,
                      "sr_name":"MP00%d","format":"Mini",
                      "lon":"19.6","lat":"46.1"}]
                    """.formatted(index, index, index));
        }
        rows.append(",").append("""
                ["9",{"place_id":"30000","status":1,
                  "sr_name":"MP900 Glavna 9, Subotica","address":"Glavna 9",
                  "grad":"Subotica","format":"Mini","lon":"19.6","lat":"46.1"}]
                ]""");

        assertThatThrownBy(() -> fetch(rows.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4 od 5");
    }

    @Test
    void readsTheStreetOutOfTheShopName() {
        assertThat(UniverexportLocationClient.addressFromName(
                "MP033 Sentandrejski put bb, Novi Sad", "Novi Sad"))
                .isEqualTo("Sentandrejski put bb");
        assertThat(UniverexportLocationClient.addressFromName(
                "MP033 Sentandrejski put bb", "Novi Sad"))
                .isEqualTo("Sentandrejski put bb");
        assertThat(UniverexportLocationClient.addressFromName(
                "MP033", "Novi Sad"))
                .isEmpty();
        assertThat(UniverexportLocationClient.addressFromName(
                "MP033 Novi Sad", "Novi Sad"))
                .isEmpty();
    }

    private static java.util.List<UniverexportApiLocation> fetch(
            String embeddedJson
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer
                .bindTo(builder)
                .build();
        JsonMapper mapper = JsonMapper.builder().build();
        server.expect(once(), requestTo("https://univer.test/locations"))
                .andRespond(withSuccess(
                        mapper.writeValueAsString(embeddedJson),
                        MediaType.APPLICATION_JSON
                ));
        return new UniverexportLocationClient(
                builder.build(),
                mapper,
                "https://univer.test/locations"
        ).fetchLocations();
    }
}
