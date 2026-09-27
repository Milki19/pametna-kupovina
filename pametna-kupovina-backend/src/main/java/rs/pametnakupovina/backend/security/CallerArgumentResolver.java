package rs.pametnakupovina.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;

/**
 * A controller that takes a {@link DeviceCaller} gets the phone the security
 * filter already proved. Endpoints that need a phone are closed to everyone
 * else before they run, so a missing caller here is a configuration mistake,
 * not a request to answer.
 */
@Component
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return DeviceCaller.class.equals(parameter.nestedIfOptional().getNestedParameterType());
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        DeviceCaller caller = DeviceCaller.of(
                webRequest.getNativeRequest(HttpServletRequest.class)
        );

        if (parameter.isOptional()) {
            return Optional.ofNullable(caller);
        }

        if (caller == null) {
            throw DeviceSessionService.rejected("Potrebna je sesija.");
        }

        return caller;
    }
}
