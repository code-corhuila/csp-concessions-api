package co.edu.corhuila.csp.concessions.domain.model;

/** Base of the typed domain errors. The domain never knows how an error is shown to a client. */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
