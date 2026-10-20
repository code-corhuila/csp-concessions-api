package co.edu.corhuila.csp.concessions.application.port.out;

import co.edu.corhuila.csp.concessions.domain.model.Product;
import java.util.Optional;
import java.util.UUID;

/** Output port: where products are kept. The implementation belongs to an adapter. */
public interface ProductRepository {

    /** Inserts or updates the product and returns what was stored. */
    Product save(Product product);

    Optional<Product> findById(UUID id);

    /** A page (starting at 1) of at most {@code limit} products, optionally only the PUBLISHED ones. */
    ProductPage findPage(int page, int limit, boolean onlyPublished);
}
