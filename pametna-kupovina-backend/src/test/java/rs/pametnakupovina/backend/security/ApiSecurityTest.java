package rs.pametnakupovina.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The admin API used to be open whenever the production profile was off.
 * Now nothing opens it but the key, and a path nobody listed is closed.
 * Runs the real Spring Security chain in front of the real controllers;
 * only the session lookup is stubbed.
 */
@SpringBootTest(properties = {
        "price-import.minimum-snapshot-date=",
        "admin.api-key=" + ApiSecurityTest.KEY
})
@Testcontainers
class ApiSecurityTest {

    static final String KEY = "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk";
    private static final DeviceCaller PHONE = new DeviceCaller(3L, 30L, "c".repeat(64));

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

    @MockitoBean
    private DeviceSessionService sessions;

    private MockMvc mvc;

    @BeforeEach
    void throughTheSameChainAsProduction() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void theAdminApiAsksForTheExactKey() throws Exception {
        assertThat(status(call("GET", "/api/v1/imports/sources"))).isEqualTo(401);
        assertThat(status(call("GET", "/api/v1/imports/sources")
                .header(CredentialsFilter.ADMIN_KEY_HEADER, KEY + "x"))).isEqualTo(401);
        assertThat(status(call("POST", "/api/v1/stores/12/geocoding-review"))).isEqualTo(401);
        assertThat(status(call("GET", "/api/v1/imports/sources")
                .header(CredentialsFilter.ADMIN_KEY_HEADER, KEY))).isEqualTo(200);
    }

    @Test
    void aPhoneSessionDoesNotOpenTheAdminApi() throws Exception {
        when(sessions.callerForAccessToken(anyString())).thenReturn(Optional.of(PHONE));

        assertThat(status(call("POST", "/api/v1/imports/daily")
                .header("Authorization", "Bearer pka_valid"))).isEqualTo(401);
    }

    @Test
    void aPhonesThingsNeedASessionAndSayHowToGetOne() throws Exception {
        when(sessions.callerForAccessToken("pka_expired")).thenReturn(Optional.empty());

        MvcResult none = mvc.perform(call("GET", "/api/v1/shopping-lists")).andReturn();
        MvcResult expired = mvc.perform(call("GET", "/api/v1/receipts")
                .header("Authorization", "Bearer pka_expired")).andReturn();

        assertThat(none.getResponse().getStatus()).isEqualTo(401);
        assertThat(none.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(expired.getResponse().getStatus()).isEqualTo(401);
        assertThat(expired.getResponse().getContentAsString()).contains("Sesija je istekla");
    }

    @Test
    void aProvedPhoneReachesTheControllerAttachedToTheRequest() throws Exception {
        when(sessions.callerForAccessToken("pka_live")).thenReturn(Optional.of(PHONE));

        MvcResult result = mvc.perform(call("GET", "/api/v1/loyalty-cards")
                .header("Authorization", "Bearer pka_live")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotIn(400, 401, 403);
        assertThat(DeviceCaller.of(result.getRequest())).isEqualTo(PHONE);
    }

    @Test
    void anOldAppIsToldToUpdateOnceItsNumberIsRetired() throws Exception {
        when(sessions.callerForLegacyToken("stari-broj")).thenReturn(Optional.empty());

        MvcResult result = mvc.perform(call("GET", "/api/v1/accounts/me")
                .header(CredentialsFilter.LEGACY_TOKEN_HEADER, "stari-broj")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("Ažuriraj aplikaciju");
    }

    @Test
    void publicPricesNeedNothingAndLookNothingUp() throws Exception {
        MvcResult search = mvc.perform(call("GET", "/api/v1/retailers")
                .header("Authorization", "Bearer pka_whatever")).andReturn();

        assertThat(search.getResponse().getStatus()).isEqualTo(200);
        assertThat(status(call("GET", "/app/index.html"))).isEqualTo(200);
        assertThat(status(call("POST", "/api/v1/sessions"))).isNotIn(401, 403);
        verify(sessions, never()).callerForAccessToken(anyString());
    }

    @Test
    void apiAnswersAreNotCachedButTheWebAppIs() throws Exception {
        MvcResult api = mvc.perform(call("GET", "/api/v1/retailers")).andReturn();
        MvcResult page = mvc.perform(call("GET", "/app/app.js")).andReturn();

        assertThat(api.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(page.getResponse().getHeader("Cache-Control")).isNull();
        assertThat(page.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void anUnlistedApiPathIsClosed() throws Exception {
        assertThat(status(call("GET", "/api/v1/something-new"))).isEqualTo(403);
        assertThat(status(call("DELETE", "/api/v1/products/5"))).isEqualTo(403);
        assertThat(status(call("GET", "/api"))).isEqualTo(403);
    }

    @Test
    void nothingIsRememberedBetweenRequests() throws Exception {
        when(sessions.callerForAccessToken("pka_live")).thenReturn(Optional.of(PHONE));

        MvcResult result = mvc.perform(call("GET", "/api/v1/loyalty-cards")
                .header("Authorization", "Bearer pka_live")).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookies()).isEmpty();
    }

    /** One path must not be spelled as another to slip past its rule. */
    @Test
    void aPathSpelledTwoWaysIsRefused() throws Exception {
        for (String trick : new String[]{
                "/api/v1/products/../imports/daily",
                "/api/v1/products/%2e%2e/imports/daily",
                "/%61pi/v1/imports/daily",
                "/api/v1//imports/daily",
                "/api/v1/imports;x=1/daily"
        }) {
            assertThat(status(call("POST", trick))).as(trick).isEqualTo(400);
        }
    }

    @Test
    void withoutAConfiguredKeyTheAdminApiAnswersNobody() throws Exception {
        CredentialsFilter keyless = new CredentialsFilter(sessions, "");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/imports/daily");
        request.addHeader(CredentialsFilter.ADMIN_KEY_HEADER, "");

        try {
            keyless.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** The URI is taken as written, so the tricks above arrive unchanged. */
    private static MockHttpServletRequestBuilder call(String method, String uri) {
        return request(HttpMethod.valueOf(method), URI.create(uri)).with(sent -> {
            sent.setRequestURI(uri);
            return sent;
        });
    }
}
