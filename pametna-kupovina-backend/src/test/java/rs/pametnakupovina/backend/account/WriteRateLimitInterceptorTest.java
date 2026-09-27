package rs.pametnakupovina.backend.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.security.DeviceCaller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nothing used to stop one phone from filling the database. Reading stays
 * free — the prices are public — and a shopper pasting a long list has to
 * pass through untouched.
 */
class WriteRateLimitInterceptorTest {

    private static final int ALLOWED = 5;

    private static final DeviceCaller FIRST = new DeviceCaller(1L, 11L, "a".repeat(64));
    private static final DeviceCaller SECOND = new DeviceCaller(2L, 21L, "b".repeat(64));

    private WriteRateLimitInterceptor interceptor;

    @BeforeEach
    void twoPhonesOnTwoAccounts() {
        interceptor = new WriteRateLimitInterceptor(ALLOWED);
    }

    @Test
    void writingMoreThanAllowedInAMinuteIsRefused() {
        for (int write = 0; write < ALLOWED; write++) {
            assertThat(handle(write(FIRST))).isTrue();
        }

        assertThatThrownBy(() -> handle(write(FIRST)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("Sačekaj minut");
    }

    @Test
    void oneAccountUsingItsShareLeavesAnotherUntouched() {
        for (int write = 0; write <= ALLOWED; write++) {
            try {
                handle(write(FIRST));
            } catch (ResponseStatusException expected) {
                // Prvi je potrošio svoje.
            }
        }

        assertThat(handle(write(SECOND))).isTrue();
    }

    @Test
    void readingIsNeverCounted() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/shopping-lists"
        );
        FIRST.attachTo(request);

        for (int read = 0; read < ALLOWED * 3; read++) {
            assertThat(handle(request)).isTrue();
        }

        assertThat(handle(write(FIRST))).isTrue();
    }

    /**
     * Which endpoint needs a phone is the security filter's business;
     * counting must not start turning anonymous calls away.
     */
    @Test
    void aWriteWithoutAPhoneIsLeftToTheEndpoint() {
        for (int write = 0; write < ALLOWED * 3; write++) {
            assertThat(handle(new MockHttpServletRequest(
                    "POST", "/api/v1/shopping-lists"
            ))).isTrue();
        }
    }

    private MockHttpServletRequest write(DeviceCaller caller) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/shopping-lists"
        );
        caller.attachTo(request);
        return request;
    }

    private boolean handle(MockHttpServletRequest request) {
        return interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()
        );
    }

    @Test
    void oneAddressIsCappedWhateverTokensItMakesUp() throws Exception {
        var perAddress = new AddressRateLimitInterceptor();
        MockHttpServletRequest script = new MockHttpServletRequest("GET", "/api/v1/products/search");
        script.setRemoteAddr("203.0.113.7");
        MockHttpServletRequest shopper = new MockHttpServletRequest("GET", "/api/v1/products/search");
        shopper.setRemoteAddr("198.51.100.4");

        // Novi izmišljeni token u svakom zahtevu ne pomaže: broji se adresa.
        for (int request = 0; request < AddressRateLimitInterceptor.REQUESTS_PER_MINUTE; request++) {
            script.removeHeader("X-Client-Token");
            script.addHeader("X-Client-Token", "izmisljen-" + request);
            assertThat(perAddress.preHandle(script, new MockHttpServletResponse(), new Object())).isTrue();
        }

        assertThatThrownBy(() -> perAddress.preHandle(script, new MockHttpServletResponse(), new Object()))
                .hasMessageContaining("429");
        assertThat(perAddress.preHandle(shopper, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
