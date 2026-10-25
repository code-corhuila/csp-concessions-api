package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.StockMovementQuery;
import co.edu.corhuila.csp.concessions.application.port.in.StockUseCases;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /stock/adjust} and {@code GET /stock/movements} of {@code concessions-service.yaml}. The first needs
 * the permission {@code concessions:inventory:write} (ADMIN) and always answers {@code 204}: a retry of the same
 * key is a success that applies nothing. The second needs {@code concessions:inventory:read} and only reads: the
 * history has no route that changes or deletes a movement. Every other answer is produced by
 * {@link ApiExceptionHandler}.
 */
@RestController
@Validated
public class StockController {

    private final StockUseCases useCases;

    public StockController(StockUseCases useCases) {
        this.useCases = useCases;
    }

    @PostMapping("/stock/adjust")
    ResponseEntity<Void> adjust(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) String idempotencyKey,
            @Valid @RequestBody StockAdjustmentRequest request,
            Caller caller) {
        if (!caller.canWriteInventory()) {
            throw new AccessDeniedException("the token does not grant " + Caller.INVENTORY_WRITE);
        }
        useCases.adjust(request.productId(), request.quantityDelta(), idempotencyKey, caller.userId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stock/movements")
    StockMovementListResponse movements(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) Instant fromDate,
            @RequestParam(required = false) Instant toDate,
            Caller caller) {
        if (!caller.canReadInventory()) {
            throw new AccessDeniedException("the token does not grant " + Caller.INVENTORY_READ);
        }
        return StockMovementListResponse.of(
                useCases.listMovements(new StockMovementQuery(page, limit, productId, fromDate, toDate)), page, limit);
    }
}
