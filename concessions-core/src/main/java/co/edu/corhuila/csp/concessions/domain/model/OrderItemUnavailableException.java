package co.edu.corhuila.csp.concessions.domain.model;

/** A requested product or combo does not exist, is not PUBLISHED, or has a component that is not. */
public class OrderItemUnavailableException extends DomainException {

    public OrderItemUnavailableException(String message) {
        super(message);
    }
}
