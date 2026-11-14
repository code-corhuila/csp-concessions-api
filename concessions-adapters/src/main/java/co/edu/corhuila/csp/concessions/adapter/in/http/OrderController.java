package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import co.edu.corhuila.csp.concessions.application.port.out.PlacedOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client order routes of {@code concessions-service.yaml} (HU-CONCESSIONS-004). Each needs its own
 * permission of the client role; an order of another user is a 404, decided by the use case. Every other
 * answer of the contract is produced by {@link ApiExceptionHandler}.
 */
@RestController
@Validated
public class OrderController {

    private final OrderUseCases useCases;

    public OrderController(OrderUseCases useCases) {
        this.useCases = useCases;
    }

    /** 201 for a new order, 200 with the original when the key already created it (Norma 5.3.8). */
    @PostMapping("/orders")
    ResponseEntity<OrderResponse> create(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody OrderRequest request,
            Caller caller) {
        require(caller.canCreateOrders(), Caller.ORDER_CREATE);
        PlacedOrder placed = useCases.create(request.toInput(caller.userId(), idempotencyKey));
        return ResponseEntity.status(placed.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(OrderResponse.of(placed.order()));
    }

    @GetMapping("/orders")
    OrderListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            Caller caller) {
        require(caller.canReadOwnOrders(), Caller.ORDER_READ_SELF);
        return OrderListResponse.of(useCases.list(caller.userId(), page, limit), page, limit);
    }

    @GetMapping("/orders/{orderId}")
    OrderResponse get(@PathVariable UUID orderId, Caller caller) {
        require(caller.canReadOwnOrders(), Caller.ORDER_READ_SELF);
        return OrderResponse.of(useCases.get(orderId, caller.userId()));
    }

    /**
     * Cancels an order of the caller. The key is only checked for its shape: the state is the guard against a
     * repeated call (a cancelled order answers 204 again, a confirmed one 409).
     */
    @PostMapping("/orders/{orderId}/cancel")
    ResponseEntity<Void> cancel(
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            Caller caller) {
        require(caller.canCancelOwnOrders(), Caller.ORDER_CANCEL_SELF);
        useCases.cancel(orderId, caller.userId());
        return ResponseEntity.noContent().build();
    }

    private static void require(boolean granted, String permission) {
        if (!granted) {
            throw new AccessDeniedException("the token does not grant " + permission);
        }
    }
}
