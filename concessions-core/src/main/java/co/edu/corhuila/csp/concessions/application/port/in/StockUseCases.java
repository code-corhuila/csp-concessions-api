package co.edu.corhuila.csp.concessions.application.port.in;

import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import java.util.UUID;

/** Input port of the stock use cases (HU-CONCESSIONS-003). */
public interface StockUseCases {

    /**
     * Records a stock receipt (positive {@code quantityDelta}) or adjustment (negative) of a product made by
     * {@code requestedBy}. Repeating the key with the same product and quantity returns the original movement
     * and applies nothing; with another body it is an idempotency conflict.
     */
    StockMovement adjust(UUID productId, int quantityDelta, String idempotencyKey, UUID requestedBy);
}
