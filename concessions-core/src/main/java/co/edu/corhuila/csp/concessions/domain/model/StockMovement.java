package co.edu.corhuila.csp.concessions.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One applied change to the stock of a product (HU-CONCESSIONS-003, ADR-043). It is immutable and is only
 * ever appended: the audit trail is the sequence of these records, each with the balance it left. The type
 * follows the sign of the change, and a malformed movement is an {@link InvalidStockMovementException}.
 */
public record StockMovement(
        UUID id,
        UUID productId,
        int quantityDelta,
        int balanceAfter,
        MovementType type,
        String idempotencyKey,
        UUID createdBy,
        Instant createdAt) {

    public StockMovement {
        if (id == null || productId == null || type == null || createdBy == null || createdAt == null) {
            throw new InvalidStockMovementException("a stock movement needs its identifiers, type and date");
        }
        if (quantityDelta == 0) {
            throw new InvalidStockMovementException("a stock movement cannot be zero");
        }
        if (balanceAfter < 0) {
            throw new InvalidStockMovementException("the balance after a movement cannot be negative");
        }
        if (MovementType.forDelta(quantityDelta) != type) {
            throw new InvalidStockMovementException("the movement type does not match the sign of the change");
        }
    }
}
