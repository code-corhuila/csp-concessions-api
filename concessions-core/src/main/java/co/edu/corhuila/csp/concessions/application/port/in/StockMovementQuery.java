package co.edu.corhuila.csp.concessions.application.port.in;

import java.time.Instant;
import java.util.UUID;

/**
 * What the administrator asks of the stock history (HU-CONCESSIONS-007): one page, and optionally the movements
 * of one product and those between two instants, both included. A {@code null} filter does not filter.
 */
public record StockMovementQuery(int page, int limit, UUID productId, Instant from, Instant to) {
}
