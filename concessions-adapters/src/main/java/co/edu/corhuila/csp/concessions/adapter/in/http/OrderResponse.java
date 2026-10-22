package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** {@code Order} of the contract. The domain object is never serialised directly; dates go out as RFC 3339 text. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OrderResponse(UUID id, UUID userId, UUID reservationId, List<Item> items, String status, long totalAmount,
                     String expiresAt) {

    record Item(UUID itemId, String itemType, int quantity, String itemName, long unitPrice) {
    }

    static OrderResponse of(ConcessionOrder order) {
        return new OrderResponse(order.id(), order.userId(), order.reservationId(),
                order.lines().stream().map(line -> new Item(line.itemId(), line.itemType().name(), line.quantity(),
                        line.name(), line.unitPrice())).toList(),
                order.status().name(), order.totalAmount(),
                order.expiresAt() == null ? null : order.expiresAt().toString());
    }
}
