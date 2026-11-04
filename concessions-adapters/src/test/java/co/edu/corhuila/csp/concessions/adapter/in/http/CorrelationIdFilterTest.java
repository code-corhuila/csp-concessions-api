package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void theCorrelationIdOfTheGatewayIsAnsweredBackAndLogged() throws Exception {
        String sent = "550e8400-e29b-41d4-a716-446655440000";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/concessions/reservations");
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, sent);
        AtomicReference<String> logged = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> logged.set(MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY)));

        assertEquals(sent, response.getHeader(CorrelationIdFilter.CORRELATION_HEADER));
        assertEquals(sent, logged.get());
    }

    @Test
    void aRequestWithoutCorrelationIdGetsAGeneratedOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/concessions/reservations");

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
        assertTrue(UUID_PATTERN.matcher(generated).matches(), "generated id is a UUID: " + generated);
    }

    @Test
    void aHeaderThatIsNotAUuidIsReplacedInsteadOfEchoed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/concessions/reservations");
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, "not-a-uuid");

        filter.doFilter(request, response, new MockFilterChain());

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
        assertNotEquals("not-a-uuid", generated);
        assertTrue(UUID_PATTERN.matcher(generated).matches(), "generated id is a UUID: " + generated);
    }

    @Test
    void theTraceIdLeavesTheMdcWhenTheRequestEnds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/concessions/reservations");

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(MDC.get(CorrelationIdFilter.TRACE_ID_MDC_KEY));
    }
}
