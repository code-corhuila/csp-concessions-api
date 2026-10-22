package co.edu.corhuila.csp.concessions.domain.model;

/** The reservation already has a live (not cancelled) concession order. */
public class ReservationAlreadyOrderedException extends DomainException {

    public ReservationAlreadyOrderedException(String message) {
        super(message);
    }
}
