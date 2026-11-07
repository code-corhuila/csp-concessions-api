package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.CreateOrderInput;
import co.edu.corhuila.csp.concessions.domain.model.ConcessionOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * The body of {@code POST /internal/workflow/orders} ({@code WorkflowOrderRequest} of the contract): the body of
 * {@code POST /orders} plus the user the order is for (ADR-069). The lines and their bounds are those of
 * {@link OrderRequest}.
 */
record WorkflowOrderRequest(
        @NotNull UUID userId,
        UUID reservationId,
        @NotEmpty @Size(max = ConcessionOrder.LINES_MAX) List<OrderRequest.@Valid @NotNull Item> items) {

    CreateOrderInput toInput(String idempotencyKey) {
        return new OrderRequest(reservationId, items).toInput(userId, idempotencyKey);
    }
}
