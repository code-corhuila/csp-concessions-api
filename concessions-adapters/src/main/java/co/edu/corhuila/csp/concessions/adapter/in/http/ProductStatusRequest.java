package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import jakarta.validation.constraints.NotNull;

/** The body of {@code PATCH /products/{productId}} ({@code ProductStatusUpdate} of the contract). */
record ProductStatusRequest(@NotNull ProductStatus status) {
}
