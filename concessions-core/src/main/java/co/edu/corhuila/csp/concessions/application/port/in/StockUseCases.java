package co.edu.corhuila.csp.concessions.application.port.in;

import co.edu.corhuila.csp.concessions.application.port.out.StockMovementPage;
import co.edu.corhuila.csp.concessions.domain.model.StockMovement;
import java.util.UUID;

/** Input port of the stock use cases (HU-CONCESSIONS-003 and HU-CONCESSIONS-007). */
public interface StockUseCases {

    /**
     * Records a stock receipt (positive {@code quantityDelta}) or adjustment (negative) of a product made by
     * {@code requestedBy}. Repeating the key with the same product and quantity returns the original movement
     * and applies nothing; with another body it is an idempotency conflict.
     */
    StockMovement adjust(UUID productId, int quantityDelta, String idempotencyKey, UUID requestedBy);

    /**
     * One page of the stock history, newest first, with the filters of the query. A page below 1, a limit outside
     * 1 to 100 or a range that ends before it starts is an {@link IllegalArgumentException}.
     */
    StockMovementPage listMovements(StockMovementQuery query);
}
