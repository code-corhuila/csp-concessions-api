package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.out.StockMovementEntry;
import co.edu.corhuila.csp.concessions.application.port.out.StockMovementPage;
import java.util.List;
import java.util.UUID;

/** {@code StockMovementListResponse} of the contract: the page and the pagination meta. */
record StockMovementListResponse(List<Movement> data, Meta meta) {

    /** {@code StockMovement} of the contract; the date is RFC 3339 in UTC. */
    record Movement(UUID id, UUID productId, String productName, int quantityDelta, String reason, String timestamp) {

        static Movement of(StockMovementEntry entry) {
            return new Movement(entry.id(), entry.productId(), entry.productName(), entry.quantityDelta(),
                    entry.reason(), entry.timestamp().toString());
        }
    }

    record Meta(int page, int limit, long total, long totalPages) {
    }

    static StockMovementListResponse of(StockMovementPage page, int number, int limit) {
        long totalPages = (page.total() + limit - 1) / limit;
        return new StockMovementListResponse(page.items().stream().map(Movement::of).toList(),
                new Meta(number, limit, page.total(), totalPages));
    }
}
