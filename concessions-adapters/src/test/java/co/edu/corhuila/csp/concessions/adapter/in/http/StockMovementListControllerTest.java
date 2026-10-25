package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.concessions.application.port.in.StockMovementQuery;
import co.edu.corhuila.csp.concessions.application.port.in.StockUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.StockMovementEntry;
import co.edu.corhuila.csp.concessions.application.port.out.StockMovementPage;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code GET /stock/movements} of {@code concessions-service.yaml} (HU-CONCESSIONS-007). */
class StockMovementListControllerTest {

    private static final UUID SUB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PRODUCT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MOVEMENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant WHEN = Instant.parse("2026-10-21T12:00:00Z");
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";

    private StockUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(StockUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new StockController(useCases))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CallerArgumentResolver())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static MockHttpServletRequestBuilder list(String permission) {
        return get("/stock/movements")
                .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB.toString())
                .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE, Set.of(permission))
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION);
    }

    private static MockHttpServletRequestBuilder asAdmin() {
        return list("concessions:inventory:read");
    }

    private static StockMovementPage onePage(long total) {
        return new StockMovementPage(List.of(new StockMovementEntry(MOVEMENT, PRODUCT, "Popcorn", 5, "RECEIPT", WHEN)),
                total);
    }

    @Test
    void anAdministratorGetsThePageWithTheFieldsOfTheContract() throws Exception {
        when(useCases.listMovements(new StockMovementQuery(1, 20, null, null, null))).thenReturn(onePage(1));

        mockMvc.perform(asAdmin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(MOVEMENT.toString()))
                .andExpect(jsonPath("$.data[0].productId").value(PRODUCT.toString()))
                .andExpect(jsonPath("$.data[0].productName").value("Popcorn"))
                .andExpect(jsonPath("$.data[0].quantityDelta").value(5))
                .andExpect(jsonPath("$.data[0].reason").value("RECEIPT"))
                .andExpect(jsonPath("$.data[0].timestamp").value("2026-10-21T12:00:00Z"))
                .andExpect(jsonPath("$.data[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$.data[0].idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.limit").value(20))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
    }

    @Test
    void theFiltersAndThePageReachTheUseCase() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T23:59:59Z");
        StockMovementQuery query = new StockMovementQuery(3, 10, PRODUCT, from, to);
        when(useCases.listMovements(query)).thenReturn(onePage(25));

        mockMvc.perform(asAdmin().param("page", "3").param("limit", "10").param("productId", PRODUCT.toString())
                        .param("fromDate", "2026-10-01T00:00:00Z").param("toDate", "2026-10-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(3))
                .andExpect(jsonPath("$.meta.limit").value(10))
                .andExpect(jsonPath("$.meta.total").value(25))
                .andExpect(jsonPath("$.meta.totalPages").value(3));

        verify(useCases).listMovements(query);
    }

    @Test
    void anEmptyHistoryIsAnEmptyPageNotAnError() throws Exception {
        when(useCases.listMovements(any())).thenReturn(new StockMovementPage(List.of(), 0));

        mockMvc.perform(asAdmin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0));
    }

    @Test
    void theWritePermissionDoesNotAllowReadingTheHistory() throws Exception {
        mockMvc.perform(list("concessions:inventory:write"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));

        verify(useCases, never()).listMovements(any());
    }

    @Test
    void aClientPermissionDoesNotAllowReadingTheHistory() throws Exception {
        mockMvc.perform(list("concessions:order:read:self"))
                .andExpect(status().isForbidden());

        verify(useCases, never()).listMovements(any());
    }

    @Test
    void aProductThatIsNotAUuidIsA400() throws Exception {
        mockMvc.perform(asAdmin().param("productId", "popcorn"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void aDateThatIsNotRfc3339IsA400() throws Exception {
        mockMvc.perform(asAdmin().param("fromDate", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void aBoundTheUseCaseRefusesIsA400WithItsMessage() throws Exception {
        when(useCases.listMovements(any())).thenThrow(new IllegalArgumentException("the range ends before it starts"));

        mockMvc.perform(asAdmin().param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("the range ends before it starts"));
    }

    @Test
    void theHistoryCannotBeChangedOrDeletedThroughHttp() throws Exception {
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.POST)) {
            mockMvc.perform(request(method, "/stock/movements/" + MOVEMENT)
                            .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB.toString())
                            .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE,
                                    Set.of("concessions:inventory:write", "concessions:inventory:read")))
                    .andExpect(status().is4xxClientError());
        }
    }
}
