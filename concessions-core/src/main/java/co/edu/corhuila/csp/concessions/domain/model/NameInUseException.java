package co.edu.corhuila.csp.concessions.domain.model;

/** Another product or combo that is not inactive already has the name: it can be reused only once that one is inactive. */
public class NameInUseException extends DomainException {

    public NameInUseException(String message) {
        super(message);
    }
}
