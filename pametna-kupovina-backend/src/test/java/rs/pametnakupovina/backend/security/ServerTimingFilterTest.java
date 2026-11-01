package rs.pametnakupovina.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ServerTimingFilterTest {

    private final ServerTimingFilter filter = new ServerTimingFilter();

    @Test
    void anAnswerSaysHowLongTheServerWorkedOnIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/products/search"), response,
                (request, answer) -> {
                    sleep(20);
                    answer.getWriter().write("{}");
                });

        String timing = response.getHeader(ServerTimingFilter.HEADER);
        assertThat(timing).matches("app;dur=\\d+\\.\\d");
        assertThat(Double.parseDouble(timing.substring("app;dur=".length()))).isGreaterThanOrEqualTo(19.0);
        assertThat(response.getContentAsString()).isEqualTo("{}");
    }

    @Test
    void anAnswerWithoutABodyIsTimedToo() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("DELETE", "/api/v1/shopping-lists/1"), response,
                (request, answer) -> ((jakarta.servlet.http.HttpServletResponse) answer).setStatus(204));

        assertThat(response.getHeader(ServerTimingFilter.HEADER)).startsWith("app;dur=");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
