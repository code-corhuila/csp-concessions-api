package co.edu.corhuila.csp.concessions.application.port.out;

/** The Idempotency-Key was already used with another request body (Norma 5.3.8). */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("the idempotency key was already used with another request");
    }
}
