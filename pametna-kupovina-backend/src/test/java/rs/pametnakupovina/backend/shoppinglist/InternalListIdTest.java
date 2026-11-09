package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Šesti test, stavka 11: broj spiska ostaje u serveru, osim za Android do 2.0.8. */
class InternalListIdTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-08T10:00:00Z");
    private static final UUID PUBLIC_ID = UUID.fromString("0e4f8c1a-9b2d-4c3e-8f7a-6b5c4d3e2f1a");

    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void theWebGetsTheListWithoutItsNumber() {
        Object body = write(list(), "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)");

        String text = json.writeValueAsString(body);
        assertThat(text).doesNotContain("\"id\":42").contains("\"publicId\":\"" + PUBLIC_ID + "\"");
        assertThat(json.readTree(text).has("id")).isFalse();
        // Stavke zadržavaju svoje brojeve: po njima se menjaju i brišu.
        assertThat(json.readTree(text).get("items").get(0).get("id").asLong()).isEqualTo(7L);
    }

    @Test
    void androidUpTo208StillGetsTheNumberItCallsTheListBy() {
        Object body = write(list(), "okhttp/4.12.0");

        assertThat(json.readTree(json.writeValueAsString(body)).get("id").asLong()).isEqualTo(42L);
    }

    @Test
    void everyListInTheOverviewLosesItsNumber() {
        Object body = write(List.of(summary(1), summary(2)), null);

        String text = json.writeValueAsString(body);
        assertThat(json.readTree(text).get(0).has("id")).isFalse();
        assertThat(json.readTree(text).get(1).has("id")).isFalse();
        assertThat(json.readTree(text).get(1).get("itemCount").asInt()).isEqualTo(2);
    }

    @Test
    void answersAboutAListLoseItsNumberToo() {
        ShoppingListMatchingResponse matching = new ShoppingListMatchingResponse(
                42L, 1, 1, 0, 0, 0, 0, true, List.of(), List.of());

        String text = json.writeValueAsString(write(matching, "Mozilla/5.0"));
        assertThat(json.readTree(text).has("listId")).isFalse();
        assertThat(json.readTree(text).get("totalItems").asInt()).isEqualTo(1);
    }

    @Test
    void whenNoAndroidCallsByNumberAnyMoreTheNumberIsOutForEveryone() {
        InternalListIdAdvice advice = new InternalListIdAdvice(new LegacyListClients(false));
        Object body = write(advice, list(), "okhttp/4.12.0");

        assertThat(json.readTree(json.writeValueAsString(body)).has("id")).isFalse();
    }

    @Test
    void otherAnswersAreLeftAsTheyAre() {
        Object other = List.of("a", "b");
        assertThat(InternalListIdAdvice.withoutListNumber(other)).isSameAs(other);
        assertThat(InternalListIdAdvice.withoutListNumber(List.of())).isEqualTo(List.of());
    }

    private Object write(Object body, String userAgent) {
        return write(new InternalListIdAdvice(new LegacyListClients(true)), body, userAgent);
    }

    private static Object write(InternalListIdAdvice advice, Object body, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shopping-lists");
        if (userAgent != null) {
            request.addHeader("User-Agent", userAgent);
        }
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null,
                new ServletServerHttpRequest(request), new ServletServerHttpResponse(new MockHttpServletResponse()));
    }

    private static ShoppingListResponse list() {
        ShoppingListItemResponse item = new ShoppingListItemResponse(
                7L, "mleko", "mleko", null, java.math.BigDecimal.ONE,
                ShoppingItemRule.FLEXIBLE_CATEGORY, ShoppingItemMatchingStatus.PENDING,
                null, null, null, null, null, null, AT, AT);
        return new ShoppingListResponse(42L, PUBLIC_ID, "Moja kupovina", AT, AT, List.of(item));
    }

    private static ShoppingListSummary summary(int items) {
        return new ShoppingListSummary((long) items, UUID.randomUUID(), "Moja kupovina", AT, AT, items);
    }
}
