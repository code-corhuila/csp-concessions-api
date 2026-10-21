package co.edu.corhuila.csp.concessions.domain.model;

/** A component of a combo is a product that does not exist or, at publication, is not PUBLISHED. */
public class ComboComponentException extends DomainException {

    public ComboComponentException(String message) {
        super(message);
    }
}
