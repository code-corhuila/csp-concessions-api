package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput;
import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput.RequestedItem;
import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemUnavailableException;
import co.edu.corhuila.csp.concessions.domain.model.OrderLine;
import co.edu.corhuila.csp.concessions.domain.model.OrderNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ReservationAlreadyOrderedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The internal operations of {@code csp-workflow} for the step {@code place-order} (ADR-069). Who may call them is
 * decided by {@code JwtAuthenticationFilter}; here only the routes and their answers.
 */
class WorkflowOrderControllerTest {

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String KEY = "6d1c3f0e-5f0b-4b8e-8d3a-2a6d1f4e9b71:place-order";
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID POPCORN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RESERVATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final String BODY = "{\"userId\":\"" + USER + "\",\"reservationId\":\"" + RESERVATION + "\",\"items\":["
            + "{\"itemId\":\"" + POPCORN + "\",\"itemType\":\"PRODUCT\",\"quantity\":2}]}";

    private OrderUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(OrderUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowOrderController(useCases))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static ConcessionOrder order() {
        return ConcessionOrder.reserve(ORDER_ID, USER, RESERVATION,
                List.of(new OrderLine(OrderItemType.PRODUCT, POPCORN, "Popcorn", 1500, 2)), Duration.ofMinutes(15), NOW);
    }

    private static MockHttpServletRequestBuilder place(String body) {
        return post("/internal/workflow/orders").header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void theWorkflowPlacesAnOrderForTheUserOfTheBodyAndGets201() throws Exception {
        when(useCases.create(any())).thenReturn(new PlacedOrder(order(), false));

        mockMvc.perform(place(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.userId").value(USER.toString()))
                .andExpect(jsonPath("$.reservationId").value(RESERVATION.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.totalAmount").value(3000));

        ArgumentCaptor<CreateOrderInput> input = ArgumentCaptor.forClass(CreateOrderInput.class);
        verify(useCases).create(input.capture());
        assertEquals(new CreateOrderInput(USER, RESERVATION,
                List.of(new RequestedItem(OrderItemType.PRODUCT, POPCORN, 2)), KEY), input.getValue());
    }

    @Test
    void aReplayOfTheSameKeyAndBodyGets200WithTheOriginalOrder() throws Exception {
        when(useCases.create(any())).thenReturn(new PlacedOrder(order(), true));

        mockMvc.perform(place(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
    }

    @Test
    void theSameKeyWithAnotherBodyIsAConflict() throws Exception {
        when(useCases.create(any())).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(place(BODY)).andExpect(status().isConflict());
    }

    @Test
    void aReservationThatAlreadyHasALiveOrderIsAConflict() throws Exception {
        when(useCases.create(any())).thenThrow(new ReservationAlreadyOrderedException("already ordered"));

        mockMvc.perform(place(BODY)).andExpect(status().isConflict());
    }

    @Test
    void insufficientStockIsAConflict() throws Exception {
        when(useCases.create(any())).thenThrow(new InsufficientStockException("not enough popcorn"));

        mockMvc.perform(place(BODY)).andExpect(status().isConflict());
    }

    @Test
    void anUnavailableItemIsA422() throws Exception {
        when(useCases.create(any())).thenThrow(new OrderItemUnavailableException("the product is not available"));

        mockMvc.perform(place(BODY)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aBodyWithoutTheUserIsA400AndPlacesNothing() throws Exception {
        String withoutUser = "{\"items\":[{\"itemId\":\"" + POPCORN + "\",\"itemType\":\"PRODUCT\",\"quantity\":2}]}";

        mockMvc.perform(place(withoutUser)).andExpect(status().isBadRequest());

        verify(useCases, never()).create(any());
    }

    @Test
    void aBodyWithoutItemsIsA400AndPlacesNothing() throws Exception {
        mockMvc.perform(place("{\"userId\":\"" + USER + "\",\"items\":[]}")).andExpect(status().isBadRequest());

        verify(useCases, never()).create(any());
    }

    @Test
    void aRequestWithoutIdempotencyKeyIsA400() throws Exception {
        mockMvc.perform(post("/internal/workflow/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());

        verify(useCases, never()).create(any());
    }

    @Test
    void theWorkflowCancelsAnOrderAndGets200WithIt() throws Exception {
        when(useCases.cancelForWorkflow(ORDER_ID)).thenReturn(order());

        mockMvc.perform(post("/internal/workflow/orders/" + ORDER_ID + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
    }

    @Test
    void cancellingAnOrderThatDoesNotExistIsA404() throws Exception {
        when(useCases.cancelForWorkflow(ORDER_ID)).thenThrow(new OrderNotFoundException("the order does not exist"));

        mockMvc.perform(post("/internal/workflow/orders/" + ORDER_ID + "/cancel"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingAConfirmedOrderIsAConflict() throws Exception {
        when(useCases.cancelForWorkflow(ORDER_ID))
                .thenThrow(new InvalidStatusTransitionException("the order is CONFIRMED and cannot be changed"));

        mockMvc.perform(post("/internal/workflow/orders/" + ORDER_ID + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void anOrderIdThatIsNotAUuidIsA400() throws Exception {
        mockMvc.perform(post("/internal/workflow/orders/nope/cancel")).andExpect(status().isBadRequest());

        verify(useCases, never()).cancelForWorkflow(any());
    }
}
