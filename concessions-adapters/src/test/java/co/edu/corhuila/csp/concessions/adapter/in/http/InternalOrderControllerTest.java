package co.edu.corhuila.csp.concessions.adapter.in.http;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.in.ReservationEventUseCases;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The internal operations of {@code csp-worker} (ADR-045). Who may call them is decided by
 * {@code JwtAuthenticationFilter}; here only the routes and their answers.
 */
class InternalOrderControllerTest {

    private static final UUID RESERVATION = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private static final UUID EVENT = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private OrderUseCases useCases;
    private ReservationEventUseCases events;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCases = mock(OrderUseCases.class);
        events = mock(ReservationEventUseCases.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalOrderController(useCases, events))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void theConfirmationOfTheReservationConfirmsItsOrder() throws Exception {
        mockMvc.perform(post("/internal/orders/by-reservation/" + RESERVATION + "/confirm"))
                .andExpect(status().isNoContent());

        verify(events).bookingConfirmed(RESERVATION, null);
    }

    @Test
    void theEventIdHeaderReachesTheUseCaseOnBothRoutes() throws Exception {
        mockMvc.perform(post("/internal/orders/by-reservation/" + RESERVATION + "/confirm").header("X-Event-Id", EVENT))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/internal/orders/by-reservation/" + RESERVATION + "/release").header("X-Event-Id", EVENT))
                .andExpect(status().isNoContent());

        verify(events).bookingConfirmed(RESERVATION, EVENT);
        verify(events).reservationExpired(RESERVATION, EVENT);
    }

    @Test
    void anEventIdThatIsNotAUuidIsA400AndAppliesNothing() throws Exception {
        mockMvc.perform(post("/internal/orders/by-reservation/" + RESERVATION + "/confirm").header("X-Event-Id", "nope"))
                .andExpect(status().isBadRequest());

        verify(events, never()).bookingConfirmed(RESERVATION, null);
    }

    @Test
    void theExpirationOfTheReservationReleasesItsOrder() throws Exception {
        mockMvc.perform(post("/internal/orders/by-reservation/" + RESERVATION + "/release"))
                .andExpect(status().isNoContent());

        verify(events).reservationExpired(RESERVATION, null);
    }

    @Test
    void theOppositeFinalStateIsAConflict() throws Exception {
        doThrow(new InvalidStatusTransitionException("the order is CANCELLED and cannot be changed"))
                .when(events).bookingConfirmed(RESERVATION, null);

        mockMvc.perform(post("/internal/orders/by-reservation/" + RESERVATION + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void aReservationThatIsNotAUuidIsA400() throws Exception {
        mockMvc.perform(post("/internal/orders/by-reservation/nope/confirm")).andExpect(status().isBadRequest());

        verify(events, never()).bookingConfirmed(RESERVATION, null);
    }

    @Test
    void theSweepUsesTheDefaultBatchAndSaysWhetherThereIsMore() throws Exception {
        when(useCases.expireOrders(100)).thenReturn(100);

        mockMvc.perform(post("/internal/orders/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(100))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void theSweepHonoursTheBatchSizeOfTheBody() throws Exception {
        when(useCases.expireOrders(10)).thenReturn(3);

        mockMvc.perform(post("/internal/orders/expire").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchSize\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(3))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void aBatchOutsideOneAndFiveHundredIsA400() throws Exception {
        for (String body : new String[] {"{\"batchSize\":0}", "{\"batchSize\":501}"}) {
            mockMvc.perform(post("/internal/orders/expire").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verify(useCases, never()).expireOrders(0);
    }
}
