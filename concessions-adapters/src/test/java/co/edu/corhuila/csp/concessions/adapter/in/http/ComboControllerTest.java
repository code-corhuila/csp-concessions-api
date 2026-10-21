package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.concessions.application.port.in.ComboUseCases;
import co.edu.corhuila.csp.concessions.application.port.in.CreateComboInput;
import co.edu.corhuila.csp.concessions.application.port.out.ComboPage;
import co.edu.corhuila.csp.concessions.application.port.out.CreateComboResult;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.domain.model.Combo;
import co.edu.corhuila.csp.concessions.domain.model.ComboComponentException;
import co.edu.corhuila.csp.concessions.domain.model.ComboItem;
import co.edu.corhuila.csp.concessions.domain.model.ComboNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ComboStatus;
import co.edu.corhuila.csp.concessions.domain.model.InvalidComboException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The three combo routes of {@code concessions-service.yaml}, with their contract answers. */
class ComboControllerTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";
    private static final String KEY = "0c9a3c3e-5f0b-4b8e-8d3a-2a6d1f4e9b71";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final UUID COMBO_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID POPCORN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String BODY = "{\"name\":\"Family\",\"description\":\"Popcorn and soda\",\"price\":4500,"
            + "\"items\":[{\"productId\":\"33333333-3333-4333-8333-333333333333\",\"quantity\":2}]}";
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private ComboUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(ComboUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ComboController(useCases))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CallerArgumentResolver())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static Combo combo(ComboStatus status) {
        return new Combo(COMBO_ID, "Family", "Popcorn and soda", 4500, List.of(new ComboItem(POPCORN, 2)), status,
                NOW, NOW);
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB)
                .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE, Set.of("concessions:combo:write"))
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION);
    }

    private static MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder request) {
        return request.requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB)
                .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE, Set.of())
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION);
    }

    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder base, String body) {
        return base.header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void anAdministratorCreatesADraftComboAndGets201() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenReturn(new CreateComboResult(combo(ComboStatus.DRAFT), false));

        mockMvc.perform(withBody(asAdmin(post("/combos")), BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(COMBO_ID.toString()))
                .andExpect(jsonPath("$.name").value("Family"))
                .andExpect(jsonPath("$.price").value(4500))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].productId").value(POPCORN.toString()))
                .andExpect(jsonPath("$.items[0].quantity").value(2));

        ArgumentCaptor<CreateComboInput> input = ArgumentCaptor.forClass(CreateComboInput.class);
        verify(useCases).create(input.capture(), eq(KEY));
        assertEquals(new CreateComboInput("Family", "Popcorn and soda", 4500, List.of(new ComboItem(POPCORN, 2))),
                input.getValue());
    }

    @Test
    void aKeyThatAlreadyCreatedTheComboAnswers200WithTheOriginal() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenReturn(new CreateComboResult(combo(ComboStatus.DRAFT), true));

        mockMvc.perform(withBody(asAdmin(post("/combos")), BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(COMBO_ID.toString()));
    }

    @Test
    void aClientCannotCreateACombo() throws Exception {
        mockMvc.perform(withBody(asClient(post("/combos")), BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));

        verify(useCases, never()).create(any(), any());
    }

    @Test
    void aBodyWithoutNamePriceOrItemsIsA400() throws Exception {
        mockMvc.perform(withBody(asAdmin(post("/combos")), "{\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isNotEmpty());
        mockMvc.perform(withBody(asAdmin(post("/combos")), "{\"name\":\"A\",\"price\":1,\"items\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(withBody(asAdmin(post("/combos")), "{\"name\":\"A\",\"price\":-1,\"items\":"
                        + "[{\"productId\":\"" + POPCORN + "\",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());

        verify(useCases, never()).create(any(), any());
    }

    @Test
    void aNonPositiveQuantityIsA400AndNothingIsCreated() throws Exception {
        mockMvc.perform(withBody(asAdmin(post("/combos")), "{\"name\":\"A\",\"price\":1,\"items\":"
                        + "[{\"productId\":\"" + POPCORN + "\",\"quantity\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verify(useCases, never()).create(any(), any());
    }

    @Test
    void aMalformedComboRefusedByTheDomainIsA400() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenThrow(new InvalidComboException("a product appears once in a combo"));

        mockMvc.perform(withBody(asAdmin(post("/combos")), BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("a product appears once in a combo"));
    }

    @Test
    void aComponentThatDoesNotExistIsA422() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenThrow(new ComboComponentException("the product does not exist"));

        mockMvc.perform(withBody(asAdmin(post("/combos")), BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.traceId").value(CORRELATION));
    }

    @Test
    void theSameKeyWithAnotherBodyIsA409() throws Exception {
        when(useCases.create(any(), eq(KEY))).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(withBody(asAdmin(post("/combos")), BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void publishingReturnsTheCombo() throws Exception {
        when(useCases.changeStatus(COMBO_ID, ComboStatus.PUBLISHED)).thenReturn(combo(ComboStatus.PUBLISHED));

        mockMvc.perform(withBody(asAdmin(patch("/combos/" + COMBO_ID)), "{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void publishingWithAnInactiveComponentIsRejectedWith422() throws Exception {
        when(useCases.changeStatus(COMBO_ID, ComboStatus.PUBLISHED))
                .thenThrow(new ComboComponentException("the product is not published"));

        mockMvc.perform(withBody(asAdmin(patch("/combos/" + COMBO_ID)), "{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("the product is not published"));
    }

    @Test
    void aMoveTheLifecycleDoesNotAllowIsA409() throws Exception {
        when(useCases.changeStatus(COMBO_ID, ComboStatus.INACTIVE))
                .thenThrow(new InvalidStatusTransitionException("only a published combo can be deactivated"));

        mockMvc.perform(withBody(asAdmin(patch("/combos/" + COMBO_ID)), "{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void anUnknownComboIsA404() throws Exception {
        when(useCases.changeStatus(COMBO_ID, ComboStatus.PUBLISHED))
                .thenThrow(new ComboNotFoundException("the combo does not exist"));

        mockMvc.perform(withBody(asAdmin(patch("/combos/" + COMBO_ID)), "{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void aClientCannotChangeTheStatusAndAStatusOutsideTheLifecycleIsA400() throws Exception {
        mockMvc.perform(withBody(asClient(patch("/combos/" + COMBO_ID)), "{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withBody(asAdmin(patch("/combos/" + COMBO_ID)), "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());

        verify(useCases, never()).changeStatus(any(), any());
    }

    @Test
    void anAdministratorListsEverythingAndAClientOnlyThePublishedOnes() throws Exception {
        when(useCases.list(anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new ComboPage(List.of(combo(ComboStatus.PUBLISHED)), 1));

        mockMvc.perform(asAdmin(get("/combos")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(COMBO_ID.toString()))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.totalPages").value(1));
        verify(useCases).list(1, 20, false);

        mockMvc.perform(asClient(get("/combos")).param("page", "2").param("limit", "5")).andExpect(status().isOk());
        verify(useCases).list(2, 5, true);
    }
}
