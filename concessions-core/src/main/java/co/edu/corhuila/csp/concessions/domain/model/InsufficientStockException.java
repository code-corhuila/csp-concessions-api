package co.edu.corhuila.csp.concessions.domain.model;

/** A withdrawal larger than the available quantity: the balance never becomes negative. */
public class InsufficientStockException extends DomainException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
