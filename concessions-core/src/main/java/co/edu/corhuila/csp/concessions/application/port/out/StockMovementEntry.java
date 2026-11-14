package co.edu.corhuila.csp.concessions.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the stock history as it was stored (ADR-043): the product with its name, the signed change, and
 * why it happened. The reason is the stored movement type, which is a text because the history also holds the
 * reservations of the orders, whose types the adjustments of {@code StockMovement} never take.
 */
public record StockMovementEntry(UUID id, UUID productId, String productName, int quantityDelta, String reason,
                                 Instant timestamp) {
}
