package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.application.port.in.CreateProductInput;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /products} ({@code ProductRequest} of the contract). The contract makes the
 * category optional while the schema requires one, so a missing category is stored as
 * {@value #DEFAULT_CATEGORY}.
 */
record ProductRequest(
        @NotBlank String name,
        String description,
        String category,
        @NotNull @Min(0) Long price,
        @Min(0) Integer availableStock) {

    static final String DEFAULT_CATEGORY = "UNCATEGORIZED";

    CreateProductInput toInput() {
        return new CreateProductInput(name, description,
                category == null || category.isBlank() ? DEFAULT_CATEGORY : category,
                price, availableStock == null ? 0 : availableStock);
    }
}
