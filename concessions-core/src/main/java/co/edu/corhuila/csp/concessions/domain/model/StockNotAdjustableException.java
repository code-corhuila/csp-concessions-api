package co.edu.corhuila.csp.concessions.domain.model;

/** The product cannot take the change: it is inactive or the balance would exceed what can be stored. */
public class StockNotAdjustableException extends DomainException {

    public StockNotAdjustableException(String message) {
        super(message);
    }
}
