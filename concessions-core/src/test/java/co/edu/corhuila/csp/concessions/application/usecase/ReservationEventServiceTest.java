package co.edu.corhuila.csp.concessions.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.InboxRepository;
import co.edu.corhuila.csp.concessions.application.port.out.TransactionRunner;
import co.edu.corhuila.csp.concessions.domain.model.InvalidStatusTransitionException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HU-CONCESSIONS-005, scenario 2: an event delivered again is acknowledged and changes nothing. */
class ReservationEventServiceTest {

    private static final UUID RESERVATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT = UUID.fromString("55555555-5555-4555-8555-555555555555");

    private final List<String> effects = new ArrayList<>();
    private final Set<UUID> recorded = new HashSet<>();
    private boolean orderRefuses;
    private ReservationEventService service;

    /** Like the database: what was recorded inside a transaction that fails is gone. */
    private final TransactionRunner transactions = work -> {
        Set<UUID> before = new HashSet<>(recorded);
        try {
            work.run();
        } catch (RuntimeException failed) {
            recorded.clear();
            recorded.addAll(before);
            throw failed;
        }
    };

    private final InboxRepository inbox = (eventId, type) -> recorded.add(eventId);

    @BeforeEach
    void setUp() {
        OrderUseCases orders = (OrderUseCases) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {OrderUseCases.class}, (proxy, method, args) -> {
                    if (method.getName().equals("confirmByReservation") && orderRefuses) {
                        throw new InvalidStatusTransitionException("the order is CANCELLED and cannot be changed");
                    }
                    effects.add(method.getName() + " " + args[0]);
                    return null;
                });
        service = new ReservationEventService(orders, inbox, transactions);
    }

    @Test
    void anEventIsAppliedOnceAndItsRedeliveryChangesNothing() {
        service.bookingConfirmed(RESERVATION, EVENT);
        service.bookingConfirmed(RESERVATION, EVENT);

        assertEquals(List.of("confirmByReservation " + RESERVATION), effects);
    }

    @Test
    void theRedeliveryOfAnExpirationChangesNothing() {
        service.reservationExpired(RESERVATION, EVENT);
        service.reservationExpired(RESERVATION, EVENT);

        assertEquals(List.of("releaseByReservation " + RESERVATION), effects);
    }

    @Test
    void twoDifferentEventsAreBothApplied() {
        service.reservationExpired(RESERVATION, EVENT);
        service.reservationExpired(RESERVATION, UUID.randomUUID());

        assertEquals(2, effects.size());
    }

    @Test
    void aCallWithoutAnEventIdIsAppliedEveryTimeAndRecordsNothing() {
        service.bookingConfirmed(RESERVATION, null);
        service.bookingConfirmed(RESERVATION, null);

        assertEquals(2, effects.size());
        assertTrue(recorded.isEmpty());
    }

    @Test
    void anEventTheOrderRefusesIsNotRecordedSoItCanBeRetried() {
        orderRefuses = true;
        assertThrows(InvalidStatusTransitionException.class, () -> service.bookingConfirmed(RESERVATION, EVENT));
        assertTrue(recorded.isEmpty());

        orderRefuses = false;
        service.bookingConfirmed(RESERVATION, EVENT);

        assertEquals(List.of("confirmByReservation " + RESERVATION), effects);
    }
}
