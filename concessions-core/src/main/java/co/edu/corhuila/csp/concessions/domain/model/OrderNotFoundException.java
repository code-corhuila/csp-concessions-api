package co.edu.corhuila.csp.concessions.domain.model;

/** There is no order with the requested identifier for the caller; another user's order is not revealed. */
public class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
