package rs.pametnakupovina.backend;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Why a request was refused is written for the person using the app ("Za
 * veličinu pakovanja izaberi jedinicu"), so a client error carries it back
 * and the phone can show it next to the item. A server fault still says
 * nothing about its cause.
 */
@RestControllerAdvice
public class ClientErrorResponses {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> refused(
            ResponseStatusException exception
    ) {
        HttpStatusCode status = exception.getStatusCode();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());

        if (status.is4xxClientError() && exception.getReason() != null) {
            body.put("message", exception.getReason());
        }

        return ResponseEntity.status(status)
                .headers(exception.getHeaders())
                .body(body);
    }
}
