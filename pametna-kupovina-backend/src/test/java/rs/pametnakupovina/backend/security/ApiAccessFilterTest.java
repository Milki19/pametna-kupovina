package rs.pametnakupovina.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin API used to be open whenever the production profile was off.
 * Now nothing opens it but the key, and a path nobody listed is closed.
 */
class ApiAccessFilterTest {

    private static final String KEY = "k".repeat(40);
    private static final DeviceCaller PHONE = new DeviceCaller(3L, 30L, "c".repeat(64));

    private final DeviceSessionService sessions = mock(DeviceSessionService.class);

    @Test
    void withoutAConfiguredKeyTheAdminApiAnswersNobody() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, "");

        MockHttpServletRequest request = request("POST", "/api/v1/imports/daily");
        request.addHeader(ApiAccessFilter.ADMIN_KEY_HEADER, "");

        assertThat(run(filter, request).getStatus()).isEqualTo(401);
        assertThat(run(filter, request("GET", "/api/v1/imports/quality/crashes")).getStatus())
                .isEqualTo(401);
    }

    @Test
    void theAdminApiAsksForTheExactKey() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);

        MockHttpServletRequest missing = request("GET", "/api/v1/imports/sources");
        MockHttpServletRequest wrong = request("GET", "/api/v1/imports/sources");
        wrong.addHeader(ApiAccessFilter.ADMIN_KEY_HEADER, KEY + "x");
        MockHttpServletRequest right = request("GET", "/api/v1/imports/sources");
        right.addHeader(ApiAccessFilter.ADMIN_KEY_HEADER, KEY);
        MockHttpServletRequest geocoding = request("POST", "/api/v1/stores/12/geocoding-review");

        assertThat(run(filter, missing).getStatus()).isEqualTo(401);
        assertThat(run(filter, wrong).getStatus()).isEqualTo(401);
        assertThat(run(filter, geocoding).getStatus()).isEqualTo(401);
        assertThat(passed(filter, right)).isTrue();
    }

    @Test
    void aPhoneSessionDoesNotOpenTheAdminApi() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);
        when(sessions.callerForAccessToken(anyString())).thenReturn(Optional.of(PHONE));

        MockHttpServletRequest request = request("POST", "/api/v1/imports/daily");
        request.addHeader("Authorization", "Bearer pka_valid");

        assertThat(run(filter, request).getStatus()).isEqualTo(401);
    }

    @Test
    void aPhonesThingsNeedASessionAndSayHowToGetOne() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);
        when(sessions.callerForAccessToken("pka_expired")).thenReturn(Optional.empty());

        MockHttpServletRequest none = request("GET", "/api/v1/shopping-lists");
        MockHttpServletRequest expired = request("GET", "/api/v1/receipts");
        expired.addHeader("Authorization", "Bearer pka_expired");

        MockHttpServletResponse noneResponse = run(filter, none);
        MockHttpServletResponse expiredResponse = run(filter, expired);

        assertThat(noneResponse.getStatus()).isEqualTo(401);
        assertThat(noneResponse.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(expiredResponse.getStatus()).isEqualTo(401);
        assertThat(expiredResponse.getContentAsString()).contains("Sesija je istekla");
    }

    @Test
    void aProvedPhoneReachesTheControllerAttachedToTheRequest() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);
        when(sessions.callerForAccessToken("pka_live")).thenReturn(Optional.of(PHONE));

        MockHttpServletRequest request = request("POST", "/api/v1/loyalty-cards");
        request.addHeader("Authorization", "Bearer pka_live");

        assertThat(passed(filter, request)).isTrue();
        assertThat(DeviceCaller.of(request)).isEqualTo(PHONE);
    }

    @Test
    void anOldAppIsToldToUpdateOnceItsNumberIsRetired() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);
        when(sessions.callerForLegacyToken("stari-broj")).thenReturn(Optional.empty());

        MockHttpServletRequest request = request("GET", "/api/v1/accounts/me");
        request.addHeader(ApiAccessFilter.LEGACY_TOKEN_HEADER, "stari-broj");

        MockHttpServletResponse response = run(filter, request);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Ažuriraj aplikaciju");
    }

    @Test
    void publicPricesNeedNothingAndLookNothingUp() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);

        MockHttpServletRequest search = request("GET", "/api/v1/products/search");
        search.addHeader("Authorization", "Bearer pka_whatever");

        assertThat(passed(filter, search)).isTrue();
        assertThat(passed(filter, request("GET", "/app/index.html"))).isTrue();
        assertThat(passed(filter, request("POST", "/api/v1/sessions"))).isTrue();
        verify(sessions, never()).callerForAccessToken(anyString());
    }

    @Test
    void anUnlistedApiPathIsClosed() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);

        assertThat(run(filter, request("GET", "/api/v1/something-new")).getStatus()).isEqualTo(403);
        assertThat(run(filter, request("DELETE", "/api/v1/products/5")).getStatus()).isEqualTo(403);
    }

    /** One path must not be spelled as another to slip past its rule. */
    @Test
    void aPathSpelledTwoWaysIsRefused() throws Exception {
        ApiAccessFilter filter = new ApiAccessFilter(sessions, KEY);

        for (String trick : new String[]{
                "/api/v1/products/../imports/daily",
                "/api/v1/products/%2e%2e/imports/daily",
                "/%61pi/v1/imports/daily",
                "/api/v1//imports/daily",
                "/api/v1/imports;x=1/daily"
        }) {
            assertThat(run(filter, request("POST", trick)).getStatus())
                    .as(trick)
                    .isEqualTo(400);
        }
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    private static MockHttpServletResponse run(ApiAccessFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static boolean passed(ApiAccessFilter filter, MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }
}
