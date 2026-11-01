package rs.pametnakupovina.backend.shoppinglist;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerMapping;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns the list in the path into its number. A number is passed on as it
 * is, and the service checks whose it is, as before. A random id is looked
 * up among the caller's own lists only: someone else's list is simply not
 * found, so a refusal never hands back the number behind it.
 */
@Component
public class ListRefArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String VARIABLE = "listId";
    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,18}");
    private static final Pattern RANDOM_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final ShoppingListRepository repository;

    public ListRefArgumentResolver(ShoppingListRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ListRef.class)
                && Long.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        @SuppressWarnings("unchecked")
        Map<String, String> variables = request == null ? null : (Map<String, String>)
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String value = variables == null ? null : variables.get(VARIABLE);

        if (value != null && NUMBER.matcher(value).matches()) {
            return Long.valueOf(value);
        }

        if (value == null || !RANDOM_ID.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neispravan ID spiska.");
        }

        DeviceCaller caller = DeviceCaller.of(request);

        if (caller == null) {
            // Not reached: every list path needs a session before it runs.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Spisak nije pronađen.");
        }

        return repository.idForPublicId(UUID.fromString(value), caller.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spisak nije pronađen."));
    }
}
