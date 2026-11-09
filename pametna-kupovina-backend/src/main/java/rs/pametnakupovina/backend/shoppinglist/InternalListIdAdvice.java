package rs.pametnakupovina.backend.shoppinglist;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Takes the list's number ({@link InternalListId}) out of the answers about
 * a list, unless the caller is an Android app that still calls lists by
 * number ({@link LegacyListClients}). The service keeps working with the
 * number; only what leaves the server changes.
 */
@ControllerAdvice(basePackageClasses = InternalListIdAdvice.class)
public class InternalListIdAdvice implements ResponseBodyAdvice<Object> {

    private static final Map<Class<?>, Rebuild> REBUILDS = new ConcurrentHashMap<>();

    private final LegacyListClients legacyClients;

    public InternalListIdAdvice(LegacyListClients legacyClients) {
        this.legacyClients = legacyClients;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (body == null) {
            return null;
        }
        HttpServletRequest servletRequest = request instanceof ServletServerHttpRequest servlet
                ? servlet.getServletRequest() : null;
        if (legacyClients.needsListNumber(servletRequest)) {
            return body;
        }
        return withoutListNumber(body);
    }

    /** The same answer without the list's number; anything else is left as it is. */
    static Object withoutListNumber(Object body) {
        if (body instanceof List<?> list) {
            if (list.isEmpty() || rebuildOf(list.getFirst().getClass()) == null) {
                return body;
            }
            return list.stream().map(InternalListIdAdvice::withoutListNumber).toList();
        }
        Rebuild rebuild = body == null ? null : rebuildOf(body.getClass());
        return rebuild == null ? body : rebuild.apply(body);
    }

    private static Rebuild rebuildOf(Class<?> type) {
        if (!type.isRecord()) {
            return null;
        }
        Rebuild rebuild = REBUILDS.computeIfAbsent(type, InternalListIdAdvice::findRebuild);
        return rebuild == Rebuild.NONE ? null : rebuild;
    }

    private static Rebuild findRebuild(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        boolean[] hidden = new boolean[components.length];
        boolean any = false;
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            hidden[i] = components[i].isAnnotationPresent(InternalListId.class);
            any |= hidden[i];
        }
        if (!any) {
            return Rebuild.NONE;
        }
        try {
            Constructor<?> canonical = type.getDeclaredConstructor(types);
            canonical.setAccessible(true);
            return new Rebuild(components, hidden, canonical);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Record without its canonical constructor: " + type, exception);
        }
    }

    private record Rebuild(RecordComponent[] components, boolean[] hidden, Constructor<?> canonical) {

        static final Rebuild NONE = new Rebuild(new RecordComponent[0], new boolean[0], null);

        Object apply(Object value) {
            try {
                Object[] arguments = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    arguments[i] = hidden[i] ? null : components[i].getAccessor().invoke(value);
                }
                return canonical.newInstance(arguments);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not leave the list number out of " + value.getClass(), exception);
            }
        }
    }
}
