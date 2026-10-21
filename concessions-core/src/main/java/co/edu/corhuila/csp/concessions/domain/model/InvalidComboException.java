package co.edu.corhuila.csp.concessions.domain.model;

/** A {@link Combo} or a {@link ComboItem} was built from values that no valid one can have. */
public class InvalidComboException extends DomainException {

    public InvalidComboException(String message) {
        super(message);
    }
}
