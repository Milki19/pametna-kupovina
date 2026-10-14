package rs.pametnakupovina.backend;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A request refused because of one field the person filled in, so the app
 * can show the reason under that field instead of only in a passing toast.
 * `field` is the request's own property name ("quantity", "name", "query").
 */
public class FieldRefusal extends ResponseStatusException {

    private final String field;

    public FieldRefusal(String field, String reason) {
        super(HttpStatus.BAD_REQUEST, reason);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
