package co.edu.corhuila.csp.concessions.adapter.in.http;

import java.util.Set;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * The only place that maps the request attributes the {@link JwtAuthenticationFilter} sets onto the
 * {@link Caller}: a controller declares the parameter and never reads the attributes itself.
 */
public class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.equals(parameter.getParameterType());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Object subject = request.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (subject == null) {
            // The filter answers 401 before any controller runs: reaching here is a wiring defect.
            throw new IllegalStateException("no authenticated user on a route that requires one");
        }
        Object permissions = request.getAttribute(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        return new Caller(UUID.fromString((String) subject),
                permissions == null ? Set.of() : (Set<String>) permissions);
    }
}
