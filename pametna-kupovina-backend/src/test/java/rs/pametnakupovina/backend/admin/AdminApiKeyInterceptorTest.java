package rs.pametnakupovina.backend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminApiKeyInterceptorTest {

    @Test
    void allowsLocalDevelopmentWhenProtectionIsDisabled() {
        AdminApiKeyInterceptor interceptor =
                new AdminApiKeyInterceptor(false, "");

        boolean allowed = interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object()
        );

        assertThat(allowed).isTrue();
    }

    @Test
    void rejectsMissingKeyWhenProtectionIsEnabled() {
        AdminApiKeyInterceptor interceptor =
                new AdminApiKeyInterceptor(true, "secret-key");

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new Object()
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(
                        ((ResponseStatusException) exception)
                                .getStatusCode()
                ).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void acceptsMatchingKey() {
        AdminApiKeyInterceptor interceptor =
                new AdminApiKeyInterceptor(true, "secret-key");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                AdminApiKeyInterceptor.HEADER_NAME,
                "secret-key"
        );

        boolean allowed = interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                new Object()
        );

        assertThat(allowed).isTrue();
    }
}
