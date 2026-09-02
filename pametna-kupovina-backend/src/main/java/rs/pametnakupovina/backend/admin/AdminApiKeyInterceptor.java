package rs.pametnakupovina.backend.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminApiKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-Admin-Key";

    private final boolean required;
    private final byte[] configuredKey;

    public AdminApiKeyInterceptor(
            @Value("${admin.api-key-required:false}") boolean required,
            @Value("${admin.api-key:}") String configuredKey
    ) {
        this.required = required;
        this.configuredKey = configuredKey
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!required) {
            return true;
        }

        if (configuredKey.length == 0) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Administratorski API ključ nije konfigurisan."
            );
        }

        String suppliedKey = request.getHeader(HEADER_NAME);

        if (suppliedKey == null || !MessageDigest.isEqual(
                configuredKey,
                suppliedKey.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Nedostaje ispravan administratorski API ključ."
            );
        }

        return true;
    }
}
