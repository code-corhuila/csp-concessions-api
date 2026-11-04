package co.edu.corhuila.csp.concessions.application.port.in;

import java.util.UUID;

/**
 * Input port: what the Booking events do to the orders (HU-CONCESSIONS-005, ADR-063). {@code eventId} is the
 * {@code eventId} of the event, or null when the caller has no event (a manual run): then only the state of the
 * order makes a repetition harmless.
 */
public interface ReservationEventUseCases {

    /** {@code BookingConfirmed}: confirms the order of the reservation. An event applied before changes nothing. */
    void bookingConfirmed(UUID reservationId, UUID eventId);

    /** {@code ReservationExpired}: cancels the order of the reservation. An event applied before changes nothing. */
    void reservationExpired(UUID reservationId, UUID eventId);
}
