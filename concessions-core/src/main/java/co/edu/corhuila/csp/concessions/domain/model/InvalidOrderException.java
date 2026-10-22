package co.edu.corhuila.csp.concessions.domain.model;

/** A malformed order, line or hold: a count, quantity, price or total outside what the rules allow. */
public class InvalidOrderException extends DomainException {

    public InvalidOrderException(String message) {
        super(message);
    }
}
