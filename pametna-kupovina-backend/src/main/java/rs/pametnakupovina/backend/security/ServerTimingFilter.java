package rs.pametnakupovina.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

/**
 * {@code Server-Timing: app;dur=…}: how long the server worked before the
 * answer started, in milliseconds. The browser's developer tools and a load
 * test then tell the server's time from the network's and the queue's.
 * Only the time is sent, nothing about the request.
 */
public class ServerTimingFilter extends OncePerRequestFilter {

    static final String HEADER = "Server-Timing";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        Timed timed = new Timed(response, System.nanoTime());
        try {
            chain.doFilter(request, timed);
        } finally {
            // An answer with no body (204, 304) never asked for a stream.
            timed.stamp();
        }
    }

    /** Stamps the time just before the first byte of the answer. */
    private static final class Timed extends HttpServletResponseWrapper {

        private final long startedAt;
        private boolean stamped;

        Timed(HttpServletResponse response, long startedAt) {
            super(response);
            this.startedAt = startedAt;
        }

        void stamp() {
            if (stamped || isCommitted()) {
                return;
            }
            stamped = true;
            double millis = (System.nanoTime() - startedAt) / 1_000_000.0;
            setHeader(HEADER, String.format(Locale.ROOT, "app;dur=%.1f", millis));
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            stamp();
            return super.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            stamp();
            return super.getWriter();
        }

        @Override
        public void flushBuffer() throws IOException {
            stamp();
            super.flushBuffer();
        }

        @Override
        public void sendError(int status) throws IOException {
            stamp();
            super.sendError(status);
        }

        @Override
        public void sendError(int status, String message) throws IOException {
            stamp();
            super.sendError(status, message);
        }
    }
}
