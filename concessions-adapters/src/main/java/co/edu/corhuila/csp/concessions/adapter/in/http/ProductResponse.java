package co.edu.corhuila.csp.concessions.adapter.in.http;

import co.edu.corhuila.csp.concessions.domain.model.Product;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** {@code Product} of the contract. The domain object is never serialised directly. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ProductResponse(UUID id, String name, String description, String category, long price, int availableStock,
                       String status) {

    static ProductResponse of(Product product) {
        return new ProductResponse(product.id(), product.name(), product.description(), product.category(),
                product.price(), product.availableStock(), product.status().name());
    }
}
