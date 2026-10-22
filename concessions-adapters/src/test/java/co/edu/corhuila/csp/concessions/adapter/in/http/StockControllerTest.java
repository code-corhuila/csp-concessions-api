package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.concessions.application.port.in.StockUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStockMovementException;
import co.edu.corhuila.csp.concessions.domain.model.MovementType;
import co.edu.corhuila.csp.concessions.domain.model.ProductNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import co.edu.corhuila.csp.concessions.domain.model.StockNotAdjustableException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code POST /stock/adjust} of {@code concessions-service.yaml}, with the answers ADR-043 gives it. */
class StockControllerTest {

    private static final UUID SUB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PRODUCT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String KEY = "0c9a3c3e-5f0b-4b8e-8d3a-2a6d1f4e9b71";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BODY = "{\"productId\":\"" + PRODUCT + "\",\"quantityDelta\":5}";

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

    private static MockHttpServletRequestBuilder adjust(String permission, String key, String body) {
        MockHttpServletRequestBuilder request = post("/stock/adjust")
                .requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB.toString())
                .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE, Set.of(permission))
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static MockHttpServletRequestBuilder asAdmin(String body) {
        return adjust("concessions:inventory:write", KEY, body);
    }

    @Test
    void anAdministratorRecordsAReceiptAndGets204WithNoBody() throws Exception {
        when(useCases.adjust(PRODUCT, 5, KEY, SUB)).thenReturn(new StockMovement(UUID.randomUUID(), PRODUCT, 5, 15,
                MovementType.RECEIPT, KEY, SUB, Instant.parse("2026-10-21T12:00:00Z")));

        mockMvc.perform(asAdmin(BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(useCases).adjust(PRODUCT, 5, KEY, SUB);
    }

    @Test
    void theProductWritePermissionIsNotEnoughToMoveStock() throws Exception {
        mockMvc.perform(adjust("concessions:product:write", KEY, BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));

        verify(useCases, never()).adjust(any(), anyInt(), any(), any());
    }

    @Test
    void aBodyWithoutProductOrQuantityIsA400ThatNamesTheFields() throws Exception {
        mockMvc.perform(asAdmin("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isNotEmpty());

        verify(useCases, never()).adjust(any(), anyInt(), any(), any());
    }

    @Test
    void aMissingKeyIsA400() throws Exception {
        mockMvc.perform(adjust("concessions:inventory:write", null, BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verify(useCases, never()).adjust(any(), anyInt(), any(), any());
    }

    @Test
    void aMovementTheDomainRefusesIsA400() throws Exception {
        when(useCases.adjust(any(), eq(0), any(), any()))
                .thenThrow(new InvalidStockMovementException("the change is between 1 and 100000 units"));

        mockMvc.perform(asAdmin("{\"productId\":\"" + PRODUCT + "\",\"quantityDelta\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("the change is between 1 and 100000 units"));
    }

    @Test
    void anUnknownProductIsA404() throws Exception {
        when(useCases.adjust(any(), anyInt(), any(), any())).thenThrow(new ProductNotFoundException("the product does not exist"));

        mockMvc.perform(asAdmin(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aWithdrawalLargerThanTheBalanceIsA409InsufficientStock() throws Exception {
        when(useCases.adjust(any(), anyInt(), any(), any())).thenThrow(new InsufficientStockException("the available quantity is 3"));

        mockMvc.perform(asAdmin("{\"productId\":\"" + PRODUCT + "\",\"quantityDelta\":-4}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("the available quantity is 3"));
    }

    @Test
    void aKeyUsedWithAnotherBodyIsA409Conflict() throws Exception {
        when(useCases.adjust(any(), anyInt(), any(), any())).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(asAdmin(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void anInactiveProductIsA422() throws Exception {
        when(useCases.adjust(any(), anyInt(), any(), any()))
                .thenThrow(new StockNotAdjustableException("an inactive product does not take stock movements"));

        mockMvc.perform(asAdmin(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void theCallerIsTheTokenSubjectNotAFieldOfTheBody() throws Exception {
        mockMvc.perform(asAdmin("{\"productId\":\"" + PRODUCT + "\",\"quantityDelta\":5,"
                + "\"createdBy\":\"99999999-9999-4999-8999-999999999999\"}")).andExpect(status().isNoContent());

        verify(useCases).adjust(PRODUCT, 5, KEY, SUB);
    }
}
