package rs.pametnakupovina.backend.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nothing used to stop one phone from filling the database. Reading stays
 * free — the prices are public — and a shopper pasting a long list has to
 * pass through untouched.
 */
class WriteRateLimitInterceptorTest {

    private static final int ALLOWED = 5;

    private final ShoppingListClientTokenPolicy tokenPolicy =
            mock(ShoppingListClientTokenPolicy.class);
    private final AccountRepository accountRepository =
            mock(AccountRepository.class);
    private WriteRateLimitInterceptor interceptor;

    @BeforeEach
    void twoPhonesOnTwoAccounts() {
        when(tokenPolicy.validateAndHash(anyString()))
                .thenAnswer(call -> "hash-of-" + call.getArgument(0));
        when(accountRepository.forDevice("hash-of-prvi")).thenReturn(1L);
        when(accountRepository.forDevice("hash-of-drugi")).thenReturn(2L);

        interceptor = new WriteRateLimitInterceptor(
                tokenPolicy, accountRepository, ALLOWED
        );
    }

    @Test
    void writingMoreThanAllowedInAMinuteIsRefused() {
        for (int write = 0; write < ALLOWED; write++) {
            assertThat(handle(write("prvi"))).isTrue();
        }

        assertThatThrownBy(() -> handle(write("prvi")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429")
                .hasMessageContaining("Sačekaj minut");
    }

    @Test
    void oneAccountUsingItsShareLeavesAnotherUntouched() {
        for (int write = 0; write <= ALLOWED; write++) {
            try {
                handle(write("prvi"));
            } catch (ResponseStatusException expected) {
                // Prvi je potrošio svoje.
            }
        }

        assertThat(handle(write("drugi"))).isTrue();
    }

    @Test
    void readingIsNeverCountedAndNeverCostsAQuery() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/shopping-lists"
        );
        request.addHeader("X-Client-Token", "prvi");

        for (int read = 0; read < ALLOWED * 3; read++) {
            assertThat(handle(request)).isTrue();
        }

        verify(accountRepository, never()).forDevice(anyString());
    }

    /**
     * Which endpoint needs a token is the endpoint's own business; counting
     * must not start turning anonymous calls away.
     */
    @Test
    void aWriteWithoutATokenIsLeftToTheEndpoint() {
        assertThat(handle(new MockHttpServletRequest(
                "POST", "/api/v1/shopping-lists"
        ))).isTrue();

        verify(accountRepository, never()).forDevice(anyString());
    }

    private MockHttpServletRequest write(String clientToken) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/shopping-lists"
        );
        request.addHeader("X-Client-Token", clientToken);
        return request;
    }

    private boolean handle(MockHttpServletRequest request) {
        return interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()
        );
    }
}
