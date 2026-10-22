package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.OrderUseCases;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal operations of {@code concessions-service.yaml} that {@code csp-worker} calls when it takes
 * {@code BookingConfirmed} and {@code ReservationExpired}, and on a schedule (ADR-045). They are not routed by
 * the gateway: {@link JwtAuthenticationFilter} lets only a service token reach {@code /internal/}, so
 * nothing here knows the caller.
 */
@RestController
@Validated
public class InternalOrderController {

    private static final int DEFAULT_BATCH = 100;

    private final OrderUseCases useCases;

    public InternalOrderController(OrderUseCases useCases) {
        this.useCases = useCases;
    }

    @PostMapping("/internal/orders/by-reservation/{reservationId}/confirm")
    ResponseEntity<Void> confirm(@PathVariable UUID reservationId) {
        useCases.confirmByReservation(reservationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/internal/orders/by-reservation/{reservationId}/release")
    ResponseEntity<Void> release(@PathVariable UUID reservationId) {
        useCases.releaseByReservation(reservationId);
        return ResponseEntity.noContent().build();
    }

    /** {@code hasMore} is true when the batch was full, so another call may find more. */
    @PostMapping("/internal/orders/expire")
    ExpireOrdersResponse expire(@Valid @RequestBody(required = false) ExpireOrdersRequest request) {
        int batchSize = request == null || request.batchSize() == null ? DEFAULT_BATCH : request.batchSize();
        int processed = useCases.expireOrders(batchSize);
        return new ExpireOrdersResponse(processed, processed >= batchSize);
    }
}
