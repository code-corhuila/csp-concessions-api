package co.edu.corhuila.csp.concessions.adapter.in.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter of every request: it takes the correlation id the gateway sends, puts it in the MDC
 * as {@code traceId} —the field every error envelope carries— and answers with the same value. When
 * the client sends none, or sends something that is not a UUID, this filter creates one, so a log
 * line and its answer are always correlatable (Anexo C, correlation checks).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationIdOf(request.getHeader(CORRELATION_HEADER));
        response.setHeader(CORRELATION_HEADER, correlationId);
        MDC.put(TRACE_ID_MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    /** The value received when it is a UUID; a new one in every other case. */
    static String correlationIdOf(String received) {
        if (received != null && UUID_PATTERN.matcher(received).matches()) {
            return received;
        }
        return UUID.randomUUID().toString();
    }
}
