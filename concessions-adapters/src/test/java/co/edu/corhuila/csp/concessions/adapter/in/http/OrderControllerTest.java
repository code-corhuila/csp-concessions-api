package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput;
import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput.RequestedItem;
import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.IdempotencyKeyConflictException;
import co.edu.corhuila.csp.concessions.application.port.out.OrderPage;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidOrderException;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemUnavailableException;
import co.edu.corhuila.csp.concessions.domain.model.OrderLine;
import co.edu.corhuila.csp.concessions.domain.model.OrderNotFoundException;
import co.edu.corhuila.csp.concessions.domain.model.ReservationAlreadyOrderedException;
import java.time.Duration;
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

/** The four client order routes of {@code concessions-service.yaml}, with their contract answers. */
class OrderControllerTest {

    private static final String SUB = "11111111-1111-4111-8111-111111111111";
    private static final String KEY = "0c9a3c3e-5f0b-4b8e-8d3a-2a6d1f4e9b71";
    private static final String CORRELATION = "550e8400-e29b-41d4-a716-446655440000";
    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID POPCORN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RESERVATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final String BODY = "{\"reservationId\":\"" + RESERVATION + "\",\"items\":["
            + "{\"itemId\":\"" + POPCORN + "\",\"itemType\":\"PRODUCT\",\"quantity\":2}]}";

    private OrderUseCases useCases;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(OrderUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(useCases))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new CallerArgumentResolver())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static ConcessionOrder order(UUID reservationId) {
        return ConcessionOrder.reserve(ORDER_ID, UUID.fromString(SUB), reservationId,
                List.of(new OrderLine(OrderItemType.PRODUCT, POPCORN, "Popcorn", 1500, 2)), Duration.ofMinutes(15), NOW);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... permissions) {
        return request.requestAttr(JwtAuthenticationFilter.USER_ID_ATTRIBUTE, SUB)
                .requestAttr(JwtAuthenticationFilter.PERMISSIONS_ATTRIBUTE, Set.of(permissions))
                .header(CorrelationIdFilter.CORRELATION_HEADER, CORRELATION);
    }

    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder base, String body) {
        return base.header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aClientCreatesAnOrderAndGets201WithTheSnapshot() throws Exception {
        when(useCases.create(any())).thenReturn(new PlacedOrder(order(RESERVATION), false));

        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.userId").value(SUB))
                .andExpect(jsonPath("$.reservationId").value(RESERVATION.toString()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.totalAmount").value(3000))
                .andExpect(jsonPath("$.items[0].itemId").value(POPCORN.toString()))
                .andExpect(jsonPath("$.items[0].itemType").value("PRODUCT"))
                .andExpect(jsonPath("$.items[0].itemName").value("Popcorn"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1500))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());

        ArgumentCaptor<CreateOrderInput> input = ArgumentCaptor.forClass(CreateOrderInput.class);
        verify(useCases).create(input.capture());
        assertEquals(new CreateOrderInput(UUID.fromString(SUB), RESERVATION,
                List.of(new RequestedItem(OrderItemType.PRODUCT, POPCORN, 2)), KEY), input.getValue());
    }

    @Test
    void anOrderWithoutReservationShowsItsExpiry() throws Exception {
        when(useCases.create(any())).thenReturn(new PlacedOrder(order(null), false));

        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"),
                        "{\"items\":[{\"itemId\":\"" + POPCORN + "\",\"itemType\":\"PRODUCT\",\"quantity\":2}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").value("2026-10-22T12:15:00Z"));
    }

    @Test
    void aKeyThatAlreadyCreatedTheOrderAnswers200WithTheOriginal() throws Exception {
        when(useCases.create(any())).thenReturn(new PlacedOrder(order(RESERVATION), true));

        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));
    }

    @Test
    void aCallerWithoutThePermissionGets403AndTheServiceIsNotCalled() throws Exception {
        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:read:self"), BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(useCases, never()).create(any());
    }

    @Test
    void aBadBodyOrAMissingKeyIsA400() throws Exception {
        String noItems = "{\"items\":[]}";
        String zero = "{\"items\":[{\"itemId\":\"" + POPCORN + "\",\"itemType\":\"PRODUCT\",\"quantity\":0}]}";
        String many = "{\"items\":[{\"itemId\":\"" + POPCORN + "\",\"itemType\":\"PRODUCT\",\"quantity\":21}]}";
        String unknownType = "{\"items\":[{\"itemId\":\"" + POPCORN + "\",\"itemType\":\"DRINK\",\"quantity\":1}]}";
        String noItemId = "{\"items\":[{\"itemType\":\"PRODUCT\",\"quantity\":1}]}";

        for (String body : List.of(noItems, zero, many, unknownType, noItemId, "{}", "not json")) {
            mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(as(post("/orders"), "concessions:order:create").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());
        verify(useCases, never()).create(any());
    }

    @Test
    void theDomainAndTheStockRefusalsKeepTheirContractCodes() throws Exception {
        when(useCases.create(any()))
                .thenThrow(new InsufficientStockException("the product has 1 units available"))
                .thenThrow(new OrderItemUnavailableException("the product is not available"))
                .thenThrow(new ReservationAlreadyOrderedException("the reservation already has a concession order"))
                .thenThrow(new IdempotencyKeyConflictException())
                .thenThrow(new InvalidOrderException("an item cannot appear twice in the same order"));

        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INSUFFICIENT_STOCK"));
        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BUSINESS_RULE_VIOLATION"));
        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CONFLICT"));
        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CONFLICT"));
        mockMvc.perform(withBody(as(post("/orders"), "concessions:order:create"), BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void aClientReadsItsOrder() throws Exception {
        when(useCases.get(ORDER_ID, UUID.fromString(SUB))).thenReturn(order(null));

        mockMvc.perform(as(get("/orders/" + ORDER_ID), "concessions:order:read:self"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.items[0].itemName").value("Popcorn"));
    }

    @Test
    void anOrderThatIsNotTheCallersIs404AndWithoutThePermission403() throws Exception {
        when(useCases.get(any(), any())).thenThrow(new OrderNotFoundException("the order does not exist"));

        mockMvc.perform(as(get("/orders/" + ORDER_ID), "concessions:order:read:self"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mockMvc.perform(as(get("/orders/" + ORDER_ID), "concessions:order:create"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get("/orders/not-a-uuid"), "concessions:order:read:self"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theHistoryIsAPageOfTheCallersOrders() throws Exception {
        when(useCases.list(UUID.fromString(SUB), 2, 5)).thenReturn(new OrderPage(List.of(order(null)), 6));

        mockMvc.perform(as(get("/orders").param("page", "2").param("limit", "5"), "concessions:order:read:self"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.limit").value(5))
                .andExpect(jsonPath("$.meta.total").value(6))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        mockMvc.perform(as(get("/orders"), "concessions:order:create")).andExpect(status().isForbidden());
    }

    @Test
    void cancellingAnOrderIs204AndAConfirmedOneIsAConflict() throws Exception {
        mockMvc.perform(withBody(as(post("/orders/" + ORDER_ID + "/cancel"), "concessions:order:cancel:self"), ""))
                .andExpect(status().isNoContent());
        verify(useCases).cancel(ORDER_ID, UUID.fromString(SUB));

        doThrow(new InvalidStatusTransitionException("only a reserved order can be cancelled"))
                .when(useCases).cancel(eq(ORDER_ID), any());
        mockMvc.perform(withBody(as(post("/orders/" + ORDER_ID + "/cancel"), "concessions:order:cancel:self"), ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void cancellingNeedsThePermissionAndTheKey() throws Exception {
        mockMvc.perform(withBody(as(post("/orders/" + ORDER_ID + "/cancel"), "concessions:order:read:self"), ""))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post("/orders/" + ORDER_ID + "/cancel"), "concessions:order:cancel:self"))
                .andExpect(status().isBadRequest());
        verify(useCases, never()).cancel(any(), any());
    }
}
