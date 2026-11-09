package rs.pametnakupovina.backend.shoppinglist;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Android up to 2.0.8 calls a list by its number and needs it in every
 * answer about the list; it says who it is only by OkHttp's own User-Agent.
 * The web and every other client call a list by its random id, so the
 * number stays inside the server for them. When no Android app calls by
 * number any more, {@code shopping-list.legacy-android-ids=false} takes the
 * number out for everyone.
 */
@Component
public class LegacyListClients {

    private static final String OKHTTP = "okhttp/";

    private final boolean enabled;

    public LegacyListClients(@Value("${shopping-list.legacy-android-ids:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean needsListNumber(HttpServletRequest request) {
        if (!enabled || request == null) {
            return false;
        }
        String agent = request.getHeader("User-Agent");
        return agent != null && agent.regionMatches(true, 0, OKHTTP, 0, OKHTTP.length());
    }
}
