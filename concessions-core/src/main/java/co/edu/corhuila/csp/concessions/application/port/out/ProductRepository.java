package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Product;
import java.util.Optional;
import java.util.UUID;

/** Output port: where products are kept. The implementation belongs to an adapter. */
public interface ProductRepository {

    /**
     * Inserts the product together with its idempotency key in one transaction. When the key is
     * already stored with the same request hash, nothing is written and the original product is
     * returned as a replay; with another hash it throws {@link IdempotencyKeyConflictException}.
     */
    CreateProductResult create(Product product, String idempotencyKey, String requestHash);

    /** Updates an existing product and returns what was stored. */
    Product save(Product product);

    Optional<Product> findById(UUID id);

    /** A page (starting at 1) of at most {@code limit} products, optionally only the PUBLISHED ones. */
    ProductPage findPage(int page, int limit, boolean onlyPublished);
}
