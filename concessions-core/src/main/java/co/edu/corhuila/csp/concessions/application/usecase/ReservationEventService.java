package co.edu.corhuila.csp.concessions.application.usecase;

import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.in.ReservationEventUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.InboxRepository;
import co.edu.corhuila.csp.concessions.application.port.out.TransactionRunner;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies a Booking event once (ADR-063). The event is recorded and the order changed in one transaction: a
 * redelivery finds the record and does nothing, and an event the order refuses (the opposite final state) rolls
 * the record back with it, so it can be retried.
 */
public class ReservationEventService implements ReservationEventUseCases {

    private static final String BOOKING_CONFIRMED = "BookingConfirmed";
    private static final String RESERVATION_EXPIRED = "ReservationExpired";

    private final OrderUseCases orders;
    private final InboxRepository inbox;
    private final TransactionRunner transactions;

    public ReservationEventService(OrderUseCases orders, InboxRepository inbox, TransactionRunner transactions) {
        this.orders = Objects.requireNonNull(orders);
        this.inbox = Objects.requireNonNull(inbox);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public void bookingConfirmed(UUID reservationId, UUID eventId) {
        applyOnce(eventId, BOOKING_CONFIRMED, () -> orders.confirmByReservation(reservationId));
    }

    @Override
    public void reservationExpired(UUID reservationId, UUID eventId) {
        applyOnce(eventId, RESERVATION_EXPIRED, () -> orders.releaseByReservation(reservationId));
    }

    private void applyOnce(UUID eventId, String eventType, Runnable effect) {
        if (eventId == null) {
            effect.run();
            return;
        }
        transactions.inTransaction(() -> {
            if (inbox.register(eventId, eventType)) {
                effect.run();
            }
        });
    }
}
