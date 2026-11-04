package co.edu.corhuila.csp.concessions.application.port.in;

import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import java.util.List;
import java.util.UUID;

/** What a client submits to create an order; {@code reservationId} is optional. */
public record CreateOrderInput(UUID userId, UUID reservationId, List<RequestedItem> items, String idempotencyKey) {

    /** A product or a combo and how many units of it. */
    public record RequestedItem(OrderItemType itemType, UUID itemId, int quantity) {
    }
}
