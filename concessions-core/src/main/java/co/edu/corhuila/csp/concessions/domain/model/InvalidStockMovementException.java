package co.edu.corhuila.csp.concessions.domain.model;

/** A stock change that no movement can describe: zero, beyond the allowed size or with an inconsistent type. */
public class InvalidStockMovementException extends DomainException {

    public InvalidStockMovementException(String message) {
        super(message);
    }
}
