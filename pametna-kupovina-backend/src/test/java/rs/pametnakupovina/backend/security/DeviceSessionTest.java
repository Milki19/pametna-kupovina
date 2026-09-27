package rs.pametnakupovina.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Sessions end: an access token after minutes, a refresh token after it is
 * used or after a long silence, and both the moment a phone signs out or is
 * removed from its account. Phones on app 1.8 keep working until they update,
 * and then their old number opens nothing.
 */
@SpringBootTest(properties = {
        "price-import.minimum-snapshot-date=",
        "admin.api-key=" + DeviceSessionTest.ADMIN_KEY
})
@Testcontainers
class DeviceSessionTest {

    static final String ADMIN_KEY = "test-admin-key-that-is-long-enough-1234";

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

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApiAccessFilter accessFilter;

    @Autowired
    private JdbcClient jdbcClient;

    private MockMvc mvc;

    @BeforeEach
    void throughTheSameFilterAsProduction() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(accessFilter)
                .build();
    }

    @Test
    void aNewPhoneGetsASessionAndOnlyItSeesItsLists() throws Exception {
        JsonNode mine = open(newDeviceToken());
        JsonNode theirs = open(newDeviceToken());

        long listId = createList(access(mine), "Moj spisak");

        assertThat(status(get("/api/v1/shopping-lists/" + listId), access(mine))).isEqualTo(200);
        assertThat(status(get("/api/v1/shopping-lists/" + listId), access(theirs))).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/shopping-lists")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mine.path("accessToken").asText()).startsWith("pka_");
        assertThat(mine.path("refreshToken").asText()).startsWith("pkr_");
        assertThat(mine.path("accessExpiresInSeconds").asLong()).isEqualTo(15 * 60);
    }

    @Test
    void onlyHashesOfTokensAreStored() throws Exception {
        JsonNode session = open(newDeviceToken());

        long plainCopies = jdbcClient.sql("""
                        SELECT COUNT(*) FROM app.device_session
                        WHERE access_token_hash = :access OR refresh_token_hash = :refresh
                        """)
                .param("access", access(session))
                .param("refresh", session.path("refreshToken").asText())
                .query(Long.class)
                .single();

        assertThat(plainCopies).isZero();
    }

    @Test
    void anExpiredAccessTokenIsRefusedAndARefreshBringsANewOne() throws Exception {
        JsonNode session = open(newDeviceToken());
        expireAccess(session);

        MvcResult refused = mvc.perform(authorized(get("/api/v1/accounts/me"), access(session))).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(401);
        assertThat(refused.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");

        JsonNode renewed = refresh(session.path("refreshToken").asText(), 200);
        assertThat(status(get("/api/v1/accounts/me"), access(renewed))).isEqualTo(200);
        assertThat(renewed.path("deviceId").asLong()).isEqualTo(session.path("deviceId").asLong());
    }

    @Test
    void aRefreshTokenThatNobodyUsedForTooLongOpensNothing() throws Exception {
        JsonNode session = open(newDeviceToken());

        jdbcClient.sql("""
                        UPDATE app.device_session
                           SET refresh_expires_at = NOW() - INTERVAL '1 second'
                         WHERE device_id = :deviceId
                        """)
                .param("deviceId", session.path("deviceId").asLong())
                .update();

        refresh(session.path("refreshToken").asText(), 401);
    }

    @Test
    void aRefreshTokenWorksOnceAndACopyUsedLaterEndsTheSession() throws Exception {
        JsonNode first = open(newDeviceToken());
        String firstRefresh = first.path("refreshToken").asText();

        JsonNode second = refresh(firstRefresh, 200);
        // The phone uses what it got, so the old token can no longer be a lost answer.
        assertThat(status(get("/api/v1/accounts/me"), access(second))).isEqualTo(200);

        refresh(firstRefresh, 401);

        // Whoever held the copy took the whole session down with it.
        assertThat(status(get("/api/v1/accounts/me"), access(second))).isEqualTo(401);
        refresh(second.path("refreshToken").asText(), 401);
    }

    @Test
    void aRefreshWhoseAnswerWasLostCanBeAskedForAgain() throws Exception {
        JsonNode first = open(newDeviceToken());
        String firstRefresh = first.path("refreshToken").asText();

        refresh(firstRefresh, 200); // odgovor se izgubio na putu
        JsonNode again = refresh(firstRefresh, 200);

        assertThat(status(get("/api/v1/accounts/me"), access(again))).isEqualTo(200);
        assertThat(status(get("/api/v1/accounts/me"), access(first))).isEqualTo(401);
    }

    @Test
    void anOldAppKeepsItsListsWhenItMovesToASessionAndItsNumberIsRetired() throws Exception {
        String oldNumber = newDeviceToken();

        MvcResult created = mvc.perform(post("/api/v1/shopping-lists")
                        .header(ApiAccessFilter.LEGACY_TOKEN_HEADER, oldNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Iz verzije 1.8\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long listId = json(created).path("id").asLong();

        JsonNode session = open(oldNumber);
        assertThat(status(get("/api/v1/shopping-lists/" + listId), access(session))).isEqualTo(200);

        // The number no longer works as a header ...
        MvcResult legacy = mvc.perform(get("/api/v1/shopping-lists")
                        .header(ApiAccessFilter.LEGACY_TOKEN_HEADER, oldNumber))
                .andReturn();
        assertThat(legacy.getResponse().getStatus()).isEqualTo(401);
        assertThat(legacy.getResponse().getContentAsString()).contains("Ažuriraj aplikaciju");

        // ... nor, once the session is in use, for a second session.
        openExpecting(oldNumber, 401);
    }

    @Test
    void anExchangeWhoseAnswerWasLostCanBeAskedForAgain() throws Exception {
        String number = newDeviceToken();

        JsonNode lost = open(number);
        JsonNode again = open(number);

        assertThat(again.path("deviceId").asLong()).isEqualTo(lost.path("deviceId").asLong());
        assertThat(status(get("/api/v1/accounts/me"), access(again))).isEqualTo(200);
        assertThat(status(get("/api/v1/accounts/me"), access(lost))).isEqualTo(401);
    }

    @Test
    void signingOutEndsTheSessionAtOnce() throws Exception {
        JsonNode session = open(newDeviceToken());

        assertThat(status(delete("/api/v1/sessions/current"), access(session))).isEqualTo(204);

        assertThat(status(get("/api/v1/accounts/me"), access(session))).isEqualTo(401);
        refresh(session.path("refreshToken").asText(), 401);
    }

    @Test
    void aPhoneRemovedFromTheHouseholdLosesAccessAndTheOtherKeepsIt() throws Exception {
        JsonNode keeper = open(newDeviceToken());
        JsonNode lost = open(newDeviceToken());

        String code = json(mvc.perform(authorized(post("/api/v1/accounts/invite"), access(keeper)))
                .andReturn()).path("code").asText();
        assertThat(mvc.perform(authorized(post("/api/v1/accounts/join"), access(lost))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        JsonNode devices = json(mvc.perform(authorized(get("/api/v1/accounts/devices"), access(keeper)))
                .andReturn());
        assertThat(devices).hasSize(2);

        long lostId = lost.path("deviceId").asLong();
        assertThat(status(delete("/api/v1/accounts/devices/" + lostId), access(keeper))).isEqualTo(204);

        assertThat(status(get("/api/v1/accounts/me"), access(lost))).isEqualTo(401);
        refresh(lost.path("refreshToken").asText(), 401);
        assertThat(status(get("/api/v1/accounts/me"), access(keeper))).isEqualTo(200);
    }

    @Test
    void aPhoneCannotRemoveAPhoneOfAnotherAccount() throws Exception {
        JsonNode mine = open(newDeviceToken());
        JsonNode stranger = open(newDeviceToken());

        assertThat(status(
                delete("/api/v1/accounts/devices/" + stranger.path("deviceId").asLong()),
                access(mine)
        )).isEqualTo(404);
        assertThat(status(get("/api/v1/accounts/me"), access(stranger))).isEqualTo(200);
    }

    @Test
    void theAdminApiAnswersOnlyTheKey() throws Exception {
        JsonNode phone = open(newDeviceToken());

        assertThat(mvc.perform(get("/api/v1/imports/quality/crashes")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(status(get("/api/v1/imports/quality/crashes"), access(phone))).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/imports/quality/crashes")
                        .header(ApiAccessFilter.ADMIN_KEY_HEADER, ADMIN_KEY))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private JsonNode open(String deviceToken) throws Exception {
        return openExpecting(deviceToken, 200);
    }

    private JsonNode openExpecting(String deviceToken, int expected) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceToken\":\"" + deviceToken + "\",\"deviceName\":\"Test telefon\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return expected == 200 ? json(result) : null;
    }

    private JsonNode refresh(String refreshToken, int expected) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        return expected == 200 ? json(result) : null;
    }

    private long createList(String accessToken, String name) throws Exception {
        MvcResult result = mvc.perform(authorized(post("/api/v1/shopping-lists"), accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json(result).path("id").asLong();
    }

    private void expireAccess(JsonNode session) {
        jdbcClient.sql("""
                        UPDATE app.device_session
                           SET access_expires_at = NOW() - INTERVAL '1 second'
                         WHERE device_id = :deviceId
                        """)
                .param("deviceId", session.path("deviceId").asLong())
                .update();
    }

    private int status(MockHttpServletRequestBuilder request, String accessToken) throws Exception {
        return mvc.perform(authorized(request, accessToken)).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String access(JsonNode session) {
        return session.path("accessToken").asText();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String newDeviceToken() {
        return UUID.randomUUID().toString();
    }
}
