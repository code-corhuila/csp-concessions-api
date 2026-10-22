package co.edu.corhuila.csp.concessions.adapter.in.http;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * The body of {@code POST /stock/adjust} ({@code StockAdjustment} of the contract). The size and the zero of
 * {@code quantityDelta} are rules of the domain, so a bad value is answered by {@code ApiExceptionHandler}
 * like any other refused movement.
 */
record StockAdjustmentRequest(@NotNull UUID productId, @NotNull Integer quantityDelta) {
}
