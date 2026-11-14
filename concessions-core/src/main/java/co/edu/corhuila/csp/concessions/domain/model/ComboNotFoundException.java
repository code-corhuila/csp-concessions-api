package co.edu.corhuila.csp.concessions.domain.model;

/** There is no combo with the requested identifier. */
public class ComboNotFoundException extends DomainException {

    public ComboNotFoundException(String message) {
        super(message);
    }
}
