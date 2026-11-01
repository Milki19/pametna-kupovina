package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The list in the path: its number as before, or its random id among the caller's own lists. */
class ListRefArgumentResolverTest {

    private final ShoppingListRepository repository = mock(ShoppingListRepository.class);
    private final ListRefArgumentResolver resolver = new ListRefArgumentResolver(repository);

    @Test
    void aNumberIsPassedOnForTheServiceToCheckAsBefore() throws Exception {
        assertThat(resolve("42", caller(7))).isEqualTo(42L);
        verifyNoInteractions(repository);
    }

    @Test
    void aRandomIdIsLookedUpAmongTheCallersOwnLists() throws Exception {
        UUID publicId = UUID.randomUUID();
        when(repository.idForPublicId(publicId, 7)).thenReturn(Optional.of(42L));

        assertThat(resolve(publicId.toString(), caller(7))).isEqualTo(42L);
        assertThat(resolve(publicId.toString().toUpperCase(), caller(7))).isEqualTo(42L);
    }

    @Test
    void someoneElsesRandomIdIsNotFoundAndItsNumberIsNotTold() {
        UUID publicId = UUID.randomUUID();
        when(repository.idForPublicId(publicId, 8)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolve(publicId.toString(), caller(8)))
                .isInstanceOfSatisfying(ResponseStatusException.class, refused -> {
                    assertThat(refused.getStatusCode().value()).isEqualTo(404);
                    assertThat(refused.getReason()).isEqualTo("Spisak nije pronađen.");
                });
    }

    @Test
    void anythingElseIsABadRequest() {
        for (String value : new String[] {"abc", "-1", "1.5", "12345678901234567890", "1234-5678"}) {
            assertThatThrownBy(() -> resolve(value, caller(7)))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            refused -> assertThat(refused.getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(repository);
    }

    @Test
    void onlyAnnotatedNumbersAreResolvedHere() throws Exception {
        assertThat(resolver.supportsParameter(parameter("annotated"))).isTrue();
        assertThat(resolver.supportsParameter(parameter("plain"))).isFalse();
    }

    private Object resolve(String value, DeviceCaller caller) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("listId", value));
        caller.attachTo(request);
        return resolver.resolveArgument(parameter("annotated"), null, new ServletWebRequest(request), null);
    }

    private static DeviceCaller caller(long accountId) {
        return new DeviceCaller(accountId, 1, "hash");
    }

    private static MethodParameter parameter(String method) throws NoSuchMethodException {
        return new MethodParameter(Endpoints.class.getDeclaredMethod(method, Long.class), 0);
    }

    @SuppressWarnings("unused")
    private static final class Endpoints {
        void annotated(@ListRef Long listId) {
        }

        void plain(Long listId) {
        }
    }
}
