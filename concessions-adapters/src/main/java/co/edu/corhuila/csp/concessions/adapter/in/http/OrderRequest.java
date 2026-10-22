package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput;
import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput.RequestedItem;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import co.edu.corhuila.csp.concessions.domain.model.OrderItemType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * The body of {@code POST /orders} ({@code OrderRequest} of the contract). The bounds are those of the
 * domain ({@link ConcessionOrder#LINES_MAX}, {@link ConcessionOrder#QUANTITY_MAX}), checked here first so a
 * bad body is a 400 before any lookup.
 */
record OrderRequest(
        UUID reservationId,
        @NotEmpty @Size(max = ConcessionOrder.LINES_MAX) List<@Valid @NotNull Item> items) {

    record Item(
            @NotNull UUID itemId,
            @NotNull OrderItemType itemType,
            @NotNull @Min(1) @Max(ConcessionOrder.QUANTITY_MAX) Integer quantity) {
    }

    CreateOrderInput toInput(UUID userId, String idempotencyKey) {
        return new CreateOrderInput(userId, reservationId,
                items.stream().map(item -> new RequestedItem(item.itemType(), item.itemId(), item.quantity())).toList(),
                idempotencyKey);
    }
}
