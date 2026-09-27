package rs.pametnakupovina.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The answers a request gets when it is turned away before any controller,
 * in the same shape as ClientErrorResponses so the app shows the message.
 */
final class Refusals {

    private Refusals() {
    }

    /** Says why a request that proved nothing was not let in. */
    static void unauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        switch (AccessRules.of(request)) {
            case ADMIN -> write(response, HttpStatus.UNAUTHORIZED,
                    "Nedostaje ispravan administratorski API ključ.", null);
            case DEVICE -> write(response, HttpStatus.UNAUTHORIZED,
                    request.getHeader(HttpHeaders.AUTHORIZATION) == null
                            && request.getHeader(CredentialsFilter.LEGACY_TOKEN_HEADER) != null
                            ? "Ažuriraj aplikaciju da bi nastavio."
                            : "Sesija je istekla. Otvori aplikaciju ponovo.",
                    DeviceSessionService.INVALID_TOKEN_CHALLENGE);
            default -> forbidden(response);
        }
    }

    static void forbidden(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Pristup nije dozvoljen.", null);
    }

    static void write(
            HttpServletResponse response,
            HttpStatus status,
            String message,
            String challenge
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        if (challenge != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        }

        response.getWriter().write("{\"status\":" + status.value()
                + ",\"message\":\"" + jsonEscape(message) + "\"}");
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(text.length());

        for (char character : text.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }

        return escaped.toString();
    }
}
