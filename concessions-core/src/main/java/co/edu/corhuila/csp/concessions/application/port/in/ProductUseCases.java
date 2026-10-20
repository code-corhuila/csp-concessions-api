package co.edu.corhuila.csp.concessions.application.port.in;

import co.edu.corhuila.csp.concessions.application.port.out.CreateProductResult;
import co.edu.corhuila.csp.concessions.application.port.out.ProductPage;
import co.edu.corhuila.csp.concessions.domain.model.Product;
import co.edu.corhuila.csp.concessions.domain.model.ProductStatus;
import java.util.UUID;

/** Input port of the product use cases (HU-CONCESSIONS-001). */
public interface ProductUseCases {

    /** The most items a page may hold (Norma 5.3.10). */
    int MAX_LIMIT = 100;

    /** Creates a DRAFT product; repeating the key with the same request returns the original one. */
    CreateProductResult create(CreateProductInput input, String idempotencyKey);

    /** Moves the product to {@code target} when the lifecycle allows it. */
    Product changeStatus(UUID productId, ProductStatus target);

    /** A page of products, newest first; {@code onlyPublished} is what a client may see. */
    ProductPage list(int page, int limit, boolean onlyPublished);
}
