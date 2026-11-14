package co.edu.corhuila.csp.concessions.domain.model;

/** A {@link Product} was built from values that no valid product can have: no name, a negative price. */
public class InvalidProductException extends DomainException {

    public InvalidProductException(String message) {
        super(message);
    }
}
