package co.edu.corhuila.csp.concessions.domain.model;

/** The product status cannot move from its current value to the requested one. */
public class InvalidStatusTransitionException extends DomainException {

    public InvalidStatusTransitionException(String message) {
        super(message);
    }
}
