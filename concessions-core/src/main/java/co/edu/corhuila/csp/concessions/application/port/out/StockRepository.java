package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import java.util.Optional;

/** Output port: where stock movements are kept and applied to the balance. The implementation belongs to an adapter. */
public interface StockRepository {

    /** The movement stored for an {@code Idempotency-Key}, if any: it tells a retry from a new request. */
    Optional<StockMovement> findByIdempotencyKey(String key);

    /**
     * Applies the movement to the balance of its product and stores it, both in one transaction, and returns
     * what was stored. The balance check must hold at the moment of the write, not only when the product was
     * read, so two concurrent withdrawals cannot both succeed: when the balance no longer allows the change
     * it throws {@link co.edu.corhuila.csp.concessions.domain.model.InsufficientStockException} and writes nothing.
     * The returned {@code balanceAfter} is the balance really left, which may differ from the expected one.
     * A key stored meanwhile by a concurrent request with another body is an {@link IdempotencyKeyConflictException}.
     */
    StockMovement append(StockMovement movement);
}
