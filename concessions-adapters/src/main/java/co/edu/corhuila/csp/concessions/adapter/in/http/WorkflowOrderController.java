package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal operations of {@code concessions-service.yaml} that {@code csp-workflow} calls in the step
 * {@code place-order} of the saga {@code book-seats} (ADR-069). They are not routed by the gateway:
 * {@link JwtAuthenticationFilter} lets only a token of {@code WORKFLOW_SUBJECTS} reach {@code /internal/workflow/},
 * so nothing here knows the caller. The user is the one the workflow sends, not the subject of the token.
 */
@RestController
@Validated
public class WorkflowOrderController {

    private final OrderUseCases useCases;

    public WorkflowOrderController(OrderUseCases useCases) {
        this.useCases = useCases;
    }

    /** 201 for a new order, 200 with the original when the key already created it (Norma 5.3.8). */
    @PostMapping("/internal/workflow/orders")
    ResponseEntity<OrderResponse> place(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody WorkflowOrderRequest request) {
        PlacedOrder placed = useCases.create(request.toInput(idempotencyKey));
        return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(OrderResponse.of(placed.order()));
    }

    /** 200 with the order, also when it was already cancelled; a confirmed order is a 409. */
    @PostMapping("/internal/workflow/orders/{orderId}/cancel")
    OrderResponse cancel(@PathVariable UUID orderId) {
        return OrderResponse.of(useCases.cancelForWorkflow(orderId));
    }
}
