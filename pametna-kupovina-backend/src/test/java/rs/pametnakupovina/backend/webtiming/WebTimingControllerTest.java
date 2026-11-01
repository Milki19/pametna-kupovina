package rs.pametnakupovina.backend.webtiming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The web app reports its waits without a session; only the known names
 * and sane durations are kept, a batch is capped and one network cannot
 * fill the table.
 */
@SpringBootTest(properties = "price-import.minimum-snapshot-date=")
@Testcontainers
class WebTimingControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private WebTimingController controller;

    @Autowired
    private JdbcClient jdbcClient;

    private MockMvc mvc;

    @BeforeEach
    void throughTheSameChainAsProduction() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private int send(String address, String json) throws Exception {
        return mvc.perform(post("/api/v1/web-timings")
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void theWebAppsWaitsAreKeptWithoutASessionAndSummedUpPerVersion() throws Exception {
        assertThat(send("10.0.0.1", """
                {"appVersion":"2.0.32","timings":[
                  {"metric":"preporuka","durationMs":1200},
                  {"metric":"preporuka","durationMs":3400.4},
                  {"metric":"korak1","durationMs":800},
                  {"metric":"nesto-drugo","durationMs":5},
                  {"metric":"pretraga","durationMs":-3},
                  {"metric":"pretraga","durationMs":9999999}
                ]}""")).isEqualTo(202);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.web_timing").query(Long.class).single()).isEqualTo(3);
        assertThat(controller.summary(7)).anySatisfy(summary -> {
            assertThat(summary.metric()).isEqualTo("preporuka");
            assertThat(summary.appVersion()).isEqualTo("2.0.32");
            assertThat(summary.times()).isEqualTo(2);
            assertThat(summary.p95Ms()).isEqualTo(3400);
        });
    }

    @Test
    void anOddVersionIsNotStoredAsWrittenAndABatchIsCapped() throws Exception {
        assertThat(send("10.0.0.2", """
                {"appVersion":"<script>","timings":[{"metric":"otvaranje","durationMs":500}]}""")).isEqualTo(202);
        assertThat(jdbcClient.sql("SELECT app_version FROM app.web_timing WHERE metric = 'otvaranje'")
                .query(String.class).list()).containsOnly("?");

        String many = "{\"appVersion\":\"2.0.32\",\"timings\":["
                + String.join(",", java.util.Collections.nCopies(21, "{\"metric\":\"korak1\",\"durationMs\":1}")) + "]}";
        assertThat(send("10.0.0.2", many)).isEqualTo(400);
    }

    @Test
    void oneNetworkCannotFillTheTable() throws Exception {
        String one = "{\"appVersion\":\"2.0.32\",\"timings\":[{\"metric\":\"pretraga\",\"durationMs\":100}]}";
        for (int i = 0; i < WebTimingController.REPORTS_PER_MINUTE; i++) {
            assertThat(send("10.0.0.3", one)).isEqualTo(202);
        }
        assertThat(send("10.0.0.3", one)).isEqualTo(429);
        assertThat(send("10.0.0.4", one)).isEqualTo(202);
    }
}
